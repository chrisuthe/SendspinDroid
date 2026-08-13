"""Verify the load-bearing properties of ci/conformance/dev_server.py.

1. The persistent identity is stable across restarts, and a corrupt or empty
   identity file is refused rather than silently replaced (a changed server_id
   invalidates every stored-pubkey pairing record).
2. A server built the way dev_server.py builds it REJECTS the legacy
   `client/hello`-first dialect that SendSpinDroid speaks today.
3. A deliberately PERMISSIVE server ACCEPTS that same connection.

Check 3 is the control that gives check 2 its meaning. Without it, a server
that was simply broken - wrong port semantics, a crash on first message, an
incompatible aiosendspin - would also "reject" the legacy client, and this
script would report success for the wrong reason.

Uses dev_server.build_server so it exercises the shipped configuration rather
than a copy of it.
"""

import asyncio
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aiohttp
from dev_server import build_server, load_or_create_identity

from aiosendspin.noise.trust_store import FileServerPairingStore

PORT_STRICT = 18927
PORT_PERMISSIVE = 18928

# A faithful reproduction of the first frame SendSpinDroid 2.0.0-Beta14 sends,
# mirroring MessageBuilder.buildClientHello. It matters that this is realistic:
# aiosendspin rejects a hello that lists player@v1 without player@v1_support,
# so a stripped-down payload would be refused for reasons that have nothing to
# do with encryption policy - making the rejection test pass for the wrong
# reason. The permissive control below is what catches that.
LEGACY_HELLO = json.dumps({
    "type": "client/hello",
    "payload": {
        "client_id": "7f3d5b1e-0000-4000-8000-000000000000",
        "name": "SendSpinDroid",
        "version": 1,
        "supported_roles": ["player@v1", "controller@v1", "metadata@v1"],
        "device_info": {
            "product_name": "SendSpinDroid",
            "manufacturer": "verify",
            "software_version": "2.0.0-Beta14",
        },
        "player@v1_support": {
            "supported_formats": [
                {"codec": "pcm", "sample_rate": 48000, "channels": 2, "bit_depth": 16},
            ],
            "buffer_capacity": 1_680_000,
            "supported_commands": ["volume", "mute"],
        },
    },
})

failures: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    if ok:
        print(f"PASS {label}")
    else:
        failures.append(f"{label}{(': ' + detail) if detail else ''}")
        print(f"FAIL {label}{(': ' + detail) if detail else ''}")


def test_identity_persistence(tmp: Path) -> None:
    key = tmp / "identity.key"
    first = load_or_create_identity(key)
    second = load_or_create_identity(key)
    check(first.peer_id == second.peer_id, "identity is stable across restarts",
          f"{first.peer_id} != {second.peer_id}")
    check(len(first.peer_id) == 43, "server_id is 43 base64url chars",
          f"got {len(first.peer_id)}")
    print(f"     server_id: {first.peer_id}")

    empty = tmp / "empty.key"
    empty.write_text("", encoding="utf-8")
    try:
        load_or_create_identity(empty)
    except SystemExit:
        check(True, "refuses to mint over an EMPTY identity file")
    else:
        check(False, "refuses to mint over an EMPTY identity file",
              "it silently generated a new identity")

    corrupt = tmp / "corrupt.key"
    corrupt.write_text("not-a-valid-base64url-key!!!", encoding="utf-8")
    try:
        load_or_create_identity(corrupt)
    except SystemExit:
        check(True, "refuses to mint over a CORRUPT identity file")
    else:
        check(False, "refuses to mint over a CORRUPT identity file",
              "it silently generated a new identity")


async def send_legacy_hello(port: int) -> tuple[str, str]:
    """Return (outcome, detail) for a legacy client/hello against `port`.

    outcome is one of: 'closed' (server rejected), 'replied' (server engaged),
    'upgrade_refused', 'transport_error', 'timeout'.
    """
    url = f"http://127.0.0.1:{port}/sendspin"
    try:
        async with aiohttp.ClientSession() as session:
            try:
                ws_ctx = session.ws_connect(url)
            except aiohttp.WSServerHandshakeError as err:
                return "upgrade_refused", str(err)
            async with ws_ctx as ws:
                await ws.send_str(LEGACY_HELLO)
                try:
                    msg = await asyncio.wait_for(ws.receive(), timeout=5.0)
                except asyncio.TimeoutError:
                    return "timeout", "no reply and no close within 5s"
                if msg.type is aiohttp.WSMsgType.ERROR:
                    # A local transport error is NOT evidence of a server-side
                    # policy decision; conflating the two is how this check
                    # would pass for the wrong reason.
                    return "transport_error", repr(ws.exception())
                if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED,
                                aiohttp.WSMsgType.CLOSING):
                    return "closed", f"close_code={ws.close_code}"
                return "replied", f"{msg.type.name}: {msg.data!r}"
    except (aiohttp.WSServerHandshakeError, aiohttp.ClientError) as err:
        return "upgrade_refused", f"{type(err).__name__}: {err}"


async def run_server(tmp: Path, port: int, *, allow_unencrypted: bool):
    identity = load_or_create_identity(tmp / f"identity-{port}.key")
    store = await FileServerPairingStore.open(tmp / f"store-{port}.json")
    server = build_server(asyncio.get_running_loop(), identity, "Verify", store,
                          allow_unencrypted=allow_unencrypted)
    await server.start_server(port=port, host="127.0.0.1", discover_clients=False)
    return server


async def close_server(server) -> None:
    try:
        await asyncio.wait_for(server.close(), timeout=5.0)
    except asyncio.TimeoutError:
        print("     (warning: server.close() timed out; see aiosendspin issue 299)")


async def test_legacy_rejected(tmp: Path) -> None:
    server = await run_server(tmp, PORT_STRICT, allow_unencrypted=False)
    try:
        outcome, detail = await send_legacy_hello(PORT_STRICT)
    finally:
        await close_server(server)
    check(outcome == "closed",
          "strict server REJECTS the legacy client/hello", f"outcome={outcome} ({detail})")


async def test_permissive_accepts(tmp: Path) -> None:
    """Control: prove the rejection above is caused by the flags, not by breakage."""
    server = await run_server(tmp, PORT_PERMISSIVE, allow_unencrypted=True)
    try:
        outcome, detail = await send_legacy_hello(PORT_PERMISSIVE)
    finally:
        await close_server(server)
    check(outcome == "replied",
          "control: permissive server ACCEPTS the same legacy client/hello",
          f"outcome={outcome} ({detail}) - if this fails, the rejection above "
          f"proves nothing about encryption policy")


async def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        test_identity_persistence(tmp)
        await test_legacy_rejected(tmp)
        await test_permissive_accepts(tmp)

    print()
    if failures:
        print(f"{len(failures)} CHECK(S) FAILED")
        for f in failures:
            print(" -", f)
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
