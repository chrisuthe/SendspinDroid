"""Verify the two load-bearing properties of ci/conformance/dev_server.py.

1. The persistent identity is stable across restarts (a changed server_id
   silently invalidates every stored-pubkey pairing record).
2. A server configured the way dev_server.py configures it rejects the legacy
   `client/hello`-first dialect that SendSpinDroid speaks today. That rejection
   IS the pre-Phase-1 baseline.
"""

import asyncio
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aiohttp
from dev_server import load_or_create_identity

from aiosendspin.noise.trust_store import FileServerPairingStore
from aiosendspin.server.server import SendspinServer

PORT = 18927


def test_identity_persistence(tmp: Path) -> None:
    key = tmp / "identity.key"
    first = load_or_create_identity(key)
    second = load_or_create_identity(key)
    assert first.peer_id == second.peer_id, "identity changed across restarts"
    assert len(first.peer_id) == 43, f"server_id must be 43 chars, got {len(first.peer_id)}"
    print(f"PASS identity persistence: {first.peer_id}")

    empty = tmp / "empty.key"
    empty.write_text("", encoding="utf-8")
    try:
        load_or_create_identity(empty)
    except SystemExit:
        print("PASS refuses to mint over an empty identity file")
    else:
        raise AssertionError("empty identity file was silently replaced")


async def test_legacy_rejected(tmp: Path) -> None:
    identity = load_or_create_identity(tmp / "identity.key")
    store = await FileServerPairingStore.open(tmp / "pairing_store.json")
    server = SendspinServer(
        asyncio.get_running_loop(),
        identity,
        "Verify",
        pairing_store=store,
        allow_unencrypted=False,
        allow_noncompliant_clients=False,
    )
    await server.start_server(port=PORT, host="127.0.0.1", discover_clients=False)
    try:
        url = f"http://127.0.0.1:{PORT}{SendspinServer.API_PATH}"
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(url) as ws:
                # Exactly what SendSpinDroid 2.0.0-Beta14 sends first today.
                await ws.send_str(json.dumps({
                    "type": "client/hello",
                    "payload": {
                        "client_id": "7f3d5b1e-0000-4000-8000-000000000000",
                        "name": "SendSpinDroid",
                        "version": 1,
                        "supported_roles": ["player@v1"],
                    },
                }))
                got_reply = None
                try:
                    msg = await asyncio.wait_for(ws.receive(), timeout=5.0)
                    got_reply = msg
                except asyncio.TimeoutError:
                    raise AssertionError("server neither replied nor closed - legacy accepted?")
        if got_reply.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED,
                              aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.ERROR):
            print(f"PASS legacy client/hello rejected (frame={got_reply.type.name})")
        else:
            raise AssertionError(
                f"legacy client/hello was ACCEPTED; server replied {got_reply.type.name}: "
                f"{got_reply.data!r}"
            )
    finally:
        try:
            await asyncio.wait_for(server.close(), timeout=5.0)
        except asyncio.TimeoutError:
            pass


async def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        test_identity_persistence(tmp)
        await test_legacy_rejected(tmp)
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
