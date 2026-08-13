"""Local aiosendspin development server that refuses the legacy dialect.

Music Assistant ships `allow_legacy_clients=true` by default, which silently
accepts the pre-encryption `client/hello`-first dialect SendSpinDroid speaks
today. That default hides exactly the failures the Phase 1 migration has to fix,
so this runner starts a server with `allow_unencrypted=False` and
`allow_noncompliant_clients=False`.

Unlike the conformance harness adapter, this server keeps a persistent identity
and a file-backed pairing store, so `server_id` and pairing records survive a
restart. Regenerating either invalidates the stored-pubkey records on the phone
and produces a handshake failure that the spec deliberately reports as a silent
close, so the identity file is created once and never overwritten.

See docs/dev-server-runbook.md.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import logging
import os
import sys
from pathlib import Path

try:
    from aiosendspin.noise.keys import Identity
    from aiosendspin.noise.pairing import PairMethod, PairingAttempt
    from aiosendspin.noise.pairing_token import decode_token
    from aiosendspin.noise.trust_store import FileServerPairingStore
    from aiosendspin.server.server import SendspinServer
except ImportError:  # pragma: no cover - environment guidance, not logic
    print(
        "aiosendspin is not installed. See docs/dev-server-runbook.md:\n"
        "  pip install 'aiosendspin[server]==9.1.0'",
        file=sys.stderr,
    )
    raise SystemExit(2) from None

LOGGER = logging.getLogger("dev_server")

DEFAULT_STATE_DIR = Path(".dev/sendspin")
DEFAULT_PORT = 8927


def b64u_decode(value: str) -> bytes:
    """Decode base64url without padding."""
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def load_or_create_identity(path: Path) -> Identity:
    """Return the persistent server identity, creating it on first run.

    Mirrors music_assistant/providers/sendspin/security.py. The file is created
    with O_EXCL so two concurrent starts cannot both mint an identity, and a
    corrupt file is never silently replaced -- a changed `server_id` invalidates
    every stored-pubkey pairing record on every paired client.
    """
    try:
        raw = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        pass
    else:
        if not raw:
            raise SystemExit(
                f"{path} is empty. Refusing to mint a new identity, because that would "
                f"invalidate every existing pairing record. Delete it deliberately if "
                f"that is what you want."
            )
        try:
            return Identity.from_private_bytes(b64u_decode(raw))
        except Exception as err:
            raise SystemExit(
                f"{path} is not a valid identity key ({err}). Refusing to overwrite it; "
                f"see docs/dev-server-runbook.md."
            ) from err

    identity = Identity.generate()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(identity.private_b64u)
    LOGGER.info("Generated a new server identity at %s", path)
    return identity


def describe_client(client) -> str:
    """One-line summary of a connected client, for the console."""
    security = getattr(client, "connection_security", None)
    roles = getattr(client, "active_role_ids", None) or ()
    return (
        f"{client.client_id}  name={client.name!r}  paired={client.is_paired}  "
        f"security={security}  roles={list(roles)}"
    )


class DevServer:
    def __init__(self, args: argparse.Namespace) -> None:
        self._args = args
        self._server: SendspinServer | None = None
        self._auto_trusted: set[str] = set()

    async def start(self) -> None:
        args = self._args
        state_dir = Path(args.state_dir)
        identity = load_or_create_identity(Path(args.identity_file or state_dir / "identity.key"))
        store_path = Path(args.pairing_store or state_dir / "pairing_store.json")
        store_path.parent.mkdir(parents=True, exist_ok=True)
        store = await FileServerPairingStore.open(store_path)

        self._server = SendspinServer(
            asyncio.get_running_loop(),
            identity,
            args.name,
            pairing_store=store,
            # The whole point of this runner: reject the legacy dialect.
            allow_unencrypted=False,
            allow_noncompliant_clients=False,
        )

        advertise = [args.advertise_ip] if args.advertise_ip else None
        await self._server.start_server(
            port=args.port,
            host=args.host,
            advertise_addresses=advertise,
            # SendSpinDroid is client-initiated. The spec forbids a client
            # advertising _sendspin._tcp in that mode, so we must not browse
            # for one either.
            discover_clients=False,
        )

        LOGGER.info("=" * 72)
        LOGGER.info("Sendspin dev server %r listening on %s:%d%s",
                    args.name, args.host, args.port, SendspinServer.API_PATH)
        LOGGER.info("server_id: %s", identity.peer_id)
        LOGGER.info("identity:  %s (do NOT delete - see runbook)", args.identity_file or state_dir / "identity.key")
        LOGGER.info("records:   %s", store_path)
        LOGGER.info("allow_unencrypted=False  allow_noncompliant_clients=False")
        if args.trust_all_unpaired:
            LOGGER.info("auto-trusting unpaired clients on connect (--trust-all-unpaired)")
        LOGGER.info("=" * 72)

    async def watch_clients(self) -> None:
        """Log connections and, when asked, auto-trust unpaired clients.

        A Sentinel-keyed client completes the handshake but is not
        playback-capable until the operator trusts it. Forgetting that step
        looks exactly like a bug in the client's server/activate handling, so
        --trust-all-unpaired exists to take it off the table during Phase 1.
        """
        assert self._server is not None
        seen: set[str] = set()
        while True:
            try:
                clients = list(self._server.connected_clients)
            except Exception:  # server shutting down
                return
            current = set()
            for client in clients:
                current.add(client.client_id)
                if client.client_id not in seen:
                    LOGGER.info("client connected: %s", describe_client(client))
                if (
                    self._args.trust_all_unpaired
                    and not client.is_paired
                    and client.client_id not in self._auto_trusted
                ):
                    await self._server.trust_unpaired(client.client_id)
                    self._auto_trusted.add(client.client_id)
                    LOGGER.info("auto-trusted unpaired client %s", client.client_id)
            for gone in seen - current:
                LOGGER.info("client disconnected: %s", gone)
            seen = current
            await asyncio.sleep(1.0)

    # -- console -----------------------------------------------------------

    async def console(self) -> None:
        loop = asyncio.get_running_loop()
        self._print_help()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                return
            parts = line.strip().split()
            if not parts:
                continue
            cmd, rest = parts[0], parts[1:]
            try:
                if cmd in ("q", "quit", "exit"):
                    return
                await self._dispatch(cmd, rest)
            except Exception as err:
                LOGGER.error("%s failed: %s", cmd, err)

    async def _dispatch(self, cmd: str, rest: list[str]) -> None:
        assert self._server is not None
        server = self._server
        if cmd in ("h", "help", "?"):
            self._print_help()
        elif cmd in ("c", "clients"):
            clients = list(server.connected_clients)
            if not clients:
                print("(no clients connected)")
            for client in clients:
                print(" ", describe_client(client))
        elif cmd == "trust":
            await server.trust_unpaired(self._resolve(rest))
            print("trusted")
        elif cmd == "untrust":
            await server.untrust_unpaired(self._resolve(rest))
            print("untrusted")
        elif cmd == "unpair":
            await server.unpair(self._resolve(rest))
            print("unpaired")
        elif cmd == "pair":
            if not rest:
                raise ValueError("usage: pair <SP:0... token>")
            token = decode_token(rest[0])
            client_id = token.client_id
            print(f"token decodes to client_id {client_id}")
            if server.get_client(client_id) is None:
                raise ValueError(
                    f"no connected client with id {client_id}; the device must be "
                    f"connected before it can be paired"
                )
            await server.initiate_pairing(
                client_id,
                PairingAttempt(PairMethod.PAIRING_PSK, pairing_psk=token.pairing_psk),
            )
            print("pairing initiated")
        else:
            print(f"unknown command {cmd!r}; try 'help'")

    def _resolve(self, rest: list[str]) -> str:
        """Resolve a command argument to a client id, defaulting to the only one."""
        assert self._server is not None
        if rest:
            return rest[0]
        clients = list(self._server.connected_clients)
        if len(clients) == 1:
            return clients[0].client_id
        raise ValueError("specify a client_id (see 'clients')")

    @staticmethod
    def _print_help() -> None:
        print(
            "\ncommands:\n"
            "  clients            list connected clients\n"
            "  trust [id]         allow an unpaired client to play (Sentinel PSK)\n"
            "  untrust [id]       revoke unpaired playback\n"
            "  pair <token>       pair using a SP:0... pairing token from the device\n"
            "  unpair [id]        drop the pairing record and tell the client\n"
            "  quit               stop the server\n"
            "id defaults to the only connected client when omitted.\n"
        )

    async def close(self) -> None:
        if self._server is None:
            return
        # aiosendspin issue 299: close() can hang for client-initiated
        # connections. Bound it so Ctrl-C does not appear to wedge.
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self._server.close(), timeout=5.0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default="Sendspin Dev", help="friendly server name")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--advertise-ip", default=None, help="IP to advertise over mDNS")
    parser.add_argument("--state-dir", default=str(DEFAULT_STATE_DIR))
    parser.add_argument("--identity-file", default=None)
    parser.add_argument("--pairing-store", default=None)
    parser.add_argument(
        "--trust-all-unpaired",
        action="store_true",
        help="auto-trust every unpaired client so it becomes playback-capable",
    )
    parser.add_argument(
        "--dump-wire",
        action="store_true",
        help="log the raw client/init and server/init bytes (for prologue debugging)",
    )
    return parser


async def run(args: argparse.Namespace) -> int:
    server = DevServer(args)
    await server.start()
    watcher = asyncio.create_task(server.watch_clients())
    try:
        await server.console()
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        watcher.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await watcher
        await server.close()
    return 0


def main() -> int:
    args = build_parser().parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.dump_wire else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    if args.dump_wire:
        # The prologue is the concatenated raw bytes of client/init and
        # server/init. Comparing our concatenation against the server's is the
        # only practical way to debug a prologue mismatch, which otherwise
        # closes the socket with no application-level error.
        logging.getLogger("aiosendspin").setLevel(logging.DEBUG)
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
