"""KKpsk2 initiator peer for the issue #189 interop test.

Uses `noiseprotocol`, the same library aiosendspin 9.1.0 depends on, so a pass
here means the hand-rolled Java/Kotlin responder agrees with the implementation
Music Assistant actually runs.

In Sendspin the server is the Noise initiator, so this script plays the server.
"""

import json
import os
import socket
import struct
import subprocess
import sys
import threading
from pathlib import Path

from noise.connection import Keypair, NoiseConnection

HERE = Path(__file__).parent
PROLOGUE = b"sendspin-spike-prologue-v1"
PROTO = b"Noise_KKpsk2_25519_ChaChaPoly_SHA256"

# Fixed keys so a failure is reproducible.
SERVER_STATIC = bytes(range(0x20, 0x40))
CLIENT_STATIC = bytes(range(0x60, 0x80))
PSK = bytes(range(0xA0, 0xC0))


def x25519_pub(private: bytes) -> bytes:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import (
        Encoding,
        PublicFormat,
    )

    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw
    )


def send_frame(conn: socket.socket, data: bytes) -> None:
    conn.sendall(struct.pack(">H", len(data)) + data)


def recv_frame(conn: socket.socket) -> bytes:
    header = conn.recv(2)
    if len(header) < 2:
        raise EOFError("peer closed")
    (length,) = struct.unpack(">H", header)
    buf = b""
    while len(buf) < length:
        chunk = conn.recv(length - len(buf))
        if not chunk:
            raise EOFError("peer closed mid-frame")
        buf += chunk
    return buf


def main() -> int:
    server_pub = x25519_pub(SERVER_STATIC)
    client_pub = x25519_pub(CLIENT_STATIC)

    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    port = listener.getsockname()[1]

    java_home = os.environ["JAVA_HOME"]
    cmd = [
        str(Path(java_home) / "bin" / "java"),
        "-cp", "bcprov.jar;.",
        "KKpsk2Responder",
        str(port),
        CLIENT_STATIC.hex(),
        server_pub.hex(),
        PSK.hex(),
        PROLOGUE.decode(),
    ]
    proc = subprocess.Popen(cmd, cwd=HERE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)

    out_lines: list[str] = []

    def drain() -> None:
        for line in proc.stdout:
            out_lines.append(line.rstrip())
            print("  [java]", line.rstrip())

    threading.Thread(target=drain, daemon=True).start()

    conn, _ = listener.accept()
    try:
        noise = NoiseConnection.from_name(PROTO)
        noise.set_as_initiator()
        noise.set_prologue(PROLOGUE)
        noise.set_keypair_from_private_bytes(Keypair.STATIC, SERVER_STATIC)
        noise.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, client_pub)
        noise.set_psks(PSK)
        noise.start_handshake()

        # Message 1 carries psk_id, exactly as Sendspin does.
        payload1 = json.dumps({"psk_id": "spike-psk-id-placeholder"}).encode()
        send_frame(conn, noise.write_message(payload1))

        payload2 = noise.read_message(recv_frame(conn))
        assert payload2 == b"{}", f"expected the literal two bytes {{}}, got {payload2!r}"
        assert noise.handshake_finished, "handshake did not complete"

        py_h = noise.get_handshake_hash()
        print(f"  [py]   h={py_h.hex()}")

        send_frame(conn, noise.encrypt(b"hello from initiator"))
        reply = noise.decrypt(recv_frame(conn))
        print(f"  [py]   transport_recv={reply.decode()}")
    finally:
        conn.close()
        listener.close()
        proc.wait(timeout=15)

    java_h = next((l.split("h=", 1)[1] for l in out_lines if "RESPONDER h=" in l), None)
    if java_h is None:
        print("\nFAIL: responder never printed a handshake hash")
        return 1
    if java_h != py_h.hex():
        print(f"\nFAIL: handshake hash mismatch\n  java={java_h}\n  py  ={py_h.hex()}")
        return 1
    if reply != b"hello from responder":
        print(f"\nFAIL: transport payload mismatch: {reply!r}")
        return 1

    print("\nPASS: KKpsk2 interop against noiseprotocol")
    print(f"  shared handshake hash h = {java_h}")
    print("  transport messages round-tripped in both directions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
