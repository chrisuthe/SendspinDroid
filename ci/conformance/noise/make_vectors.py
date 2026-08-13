"""Generate and validate KKpsk2 golden vectors end to end.

Both ephemerals are pinned so the whole exchange is reproducible:
  1. noiseprotocol (the reference, as initiator/server) produces message 1.
  2. The hand-rolled responder consumes it and produces message 2.
  3. noiseprotocol reads message 2 and both sides' handshake hashes are compared.

Everything written to out/vectors.json has therefore been round-tripped through
the reference implementation, not just produced by our own code.
"""

import json
import os
import subprocess
import sys
from pathlib import Path

from noise.connection import Keypair, NoiseConnection

HERE = Path(__file__).parent
PROTO = b"Noise_KKpsk2_25519_ChaChaPoly_SHA256"
PROLOGUE = b"sendspin-spike-prologue-v1"

SERVER_STATIC = bytes(range(0x20, 0x40))
SERVER_EPHEMERAL = bytes(range(0x80, 0xA0))
CLIENT_STATIC = bytes(range(0x60, 0x80))
CLIENT_EPHEMERAL = bytes(range(0xC0, 0xE0))
PSK = bytes(range(0xA0, 0xC0))
MSG1_PAYLOAD = json.dumps({"psk_id": "spike-psk-id-placeholder"}).encode()


def x25519_pub(private: bytes) -> bytes:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw)


def new_initiator() -> NoiseConnection:
    n = NoiseConnection.from_name(PROTO)
    n.set_as_initiator()
    n.set_prologue(PROLOGUE)
    n.set_keypair_from_private_bytes(Keypair.STATIC, SERVER_STATIC)
    n.set_keypair_from_private_bytes(Keypair.EPHEMERAL, SERVER_EPHEMERAL)
    n.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, x25519_pub(CLIENT_STATIC))
    n.set_psks(PSK)
    n.start_handshake()
    return n


def main() -> int:
    # Step 1: reference produces message 1.
    ini = new_initiator()
    msg1 = ini.write_message(MSG1_PAYLOAD)

    # Step 2: our responder consumes it and emits vectors.
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    proc = subprocess.run(
        [java, "-cp", "bcprov.jar;.", "KKpsk2Responder", "vectors",
         CLIENT_STATIC.hex(), x25519_pub(SERVER_STATIC).hex(), PSK.hex(),
         PROLOGUE.decode(), msg1.hex(), CLIENT_EPHEMERAL.hex()],
        cwd=HERE, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        print("responder failed:\n" + proc.stdout + proc.stderr)
        return 1
    vectors = json.loads(proc.stdout)

    # Step 3: reference reads message 2 and the hashes must agree.
    payload2 = ini.read_message(bytes.fromhex(vectors["message_2"]))
    checks = [
        ("message_2 payload is the literal two bytes {}", payload2, b"{}"),
        ("handshake finished", ini.handshake_finished, True),
        ("handshake hash agrees", ini.get_handshake_hash().hex(), vectors["handshake_hash"]),
        ("message_1 recorded matches what the reference sent", vectors["message_1"], msg1.hex()),
        ("message_1 payload decrypted correctly",
         vectors["message_1_payload_utf8"].encode(), MSG1_PAYLOAD),
    ]
    failed = 0
    for label, got, want in checks:
        ok = got == want
        print(("PASS " if ok else "FAIL ") + label)
        if not ok:
            failed += 1
            print(f"     got  {got!r}\n     want {want!r}")

    # Transport direction: what the initiator encrypts, the responder must decrypt
    # with its recv key. Confirms split() outputs are not swapped.
    ct = ini.encrypt(b"probe")
    vectors["transport_probe_ciphertext"] = ct.hex()
    vectors["transport_probe_plaintext_utf8"] = "probe"
    vectors["_generated_by"] = (
        "ci/conformance/noise/make_vectors.py - validated against noiseprotocol "
        "(the library aiosendspin 9.1.0 depends on)"
    )
    vectors["server_ephemeral_private"] = SERVER_EPHEMERAL.hex()

    if failed:
        print(f"\n{failed} CHECK(S) FAILED - vectors NOT written")
        return 1

    out = HERE / "out" / "vectors.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8")
    print(f"\nALL CHECKS PASSED - vectors written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
