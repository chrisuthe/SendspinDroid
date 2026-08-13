"""Generate and validate KKpsk2 golden vectors end to end.

Both ephemerals are pinned so the whole exchange is reproducible:
  1. noiseprotocol (the reference, as initiator/server) produces message 1.
  2. The hand-rolled responder consumes it and produces message 2.
  3. noiseprotocol reads message 2, and every field the responder emitted is
     checked against the reference before anything is written.

The transport keys get particular attention. The handshake hash does NOT depend
on split() direction, so an implementation that swapped recvKey/sendKey would
still agree on `h`. Both directions are therefore probed explicitly, at nonce 0
AND nonce 1 - nonce 0 encodes as twelve zero bytes under any endianness or
offset, so a counter bug is invisible until the second message.

Writes ci/conformance/noise/vectors.json only if every check passes.
"""

import json
import os
import subprocess
import sys
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
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


def noise_nonce(counter: int) -> bytes:
    """ChaChaPoly nonce: 4 zero bytes then the counter, 64-bit little-endian."""
    return b"\x00" * 4 + counter.to_bytes(8, "little")


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

    # Step 2: our responder consumes it and emits candidate vectors.
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    classpath = os.pathsep.join(["bcprov.jar", "."])
    proc = subprocess.run(
        [java, "-cp", classpath, "KKpsk2Responder", "vectors",
         CLIENT_STATIC.hex(), x25519_pub(SERVER_STATIC).hex(), PSK.hex(),
         PROLOGUE.decode(), msg1.hex(), CLIENT_EPHEMERAL.hex()],
        cwd=HERE, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        print("responder failed:\n" + proc.stdout + proc.stderr)
        return 1
    try:
        vectors = json.loads(proc.stdout)
    except json.JSONDecodeError as err:
        print(f"responder did not emit JSON ({err}); stdout was:\n{proc.stdout!r}")
        return 1

    checks: list[tuple[str, object, object]] = []

    # Step 3: reference reads message 2 and the handshake must agree.
    payload2 = ini.read_message(bytes.fromhex(vectors["message_2"]))
    checks += [
        ("message_2 payload is the literal two bytes {}", payload2, b"{}"),
        ("handshake finished", ini.handshake_finished, True),
        ("handshake hash agrees", ini.get_handshake_hash().hex(), vectors["handshake_hash"]),
        ("message_1 recorded matches what the reference sent", vectors["message_1"], msg1.hex()),
        ("message_1 payload decrypted correctly",
         vectors["message_1_payload_utf8"].encode(), MSG1_PAYLOAD),
    ]

    # Step 4: prove the transport keys, in BOTH directions and at TWO nonces.
    # Without this, swapping recvKey/sendKey in the responder still produces an
    # agreeing handshake hash and these vectors would ship the wrong keys as
    # ground truth for the Kotlin port.
    recv_key = bytes.fromhex(vectors["transport_key_recv"])
    send_key = bytes.fromhex(vectors["transport_key_send"])

    # initiator -> responder: the reference encrypts, recv_key must decrypt.
    probes_i2r = [ini.encrypt(b"probe-0"), ini.encrypt(b"probe-1")]
    for i, ct in enumerate(probes_i2r):
        try:
            got = ChaCha20Poly1305(recv_key).decrypt(noise_nonce(i), ct, b"")
        except Exception as err:
            got = f"DECRYPT FAILED: {err}"
        checks.append((f"transport_key_recv decrypts initiator frame n={i}",
                       got, f"probe-{i}".encode()))

    # responder -> initiator: send_key encrypts, the reference must decrypt.
    probes_r2i = [
        ChaCha20Poly1305(send_key).encrypt(noise_nonce(i), f"reply-{i}".encode(), b"")
        for i in range(2)
    ]
    for i, ct in enumerate(probes_r2i):
        try:
            got = ini.decrypt(ct)
        except Exception as err:
            got = f"DECRYPT FAILED: {err}"
        checks.append((f"reference decrypts responder frame n={i} under transport_key_send",
                       got, f"reply-{i}".encode()))

    failed = 0
    for label, got, want in checks:
        ok = got == want
        print(("PASS " if ok else "FAIL ") + label)
        if not ok:
            failed += 1
            print(f"     got  {got!r}\n     want {want!r}")

    if failed:
        print(f"\n{failed} CHECK(S) FAILED - vectors NOT written")
        return 1

    vectors["server_ephemeral_private"] = SERVER_EPHEMERAL.hex()
    vectors["transport_probe_i2r"] = [ct.hex() for ct in probes_i2r]
    vectors["transport_probe_i2r_plaintext_utf8"] = ["probe-0", "probe-1"]
    vectors["transport_probe_r2i"] = [ct.hex() for ct in probes_r2i]
    vectors["transport_probe_r2i_plaintext_utf8"] = ["reply-0", "reply-1"]
    vectors["_generated_by"] = (
        "ci/conformance/noise/make_vectors.py - every field round-tripped through "
        "noiseprotocol (the library aiosendspin 9.1.0 depends on)"
    )

    out = HERE / "vectors.json"
    new = json.dumps(vectors, indent=2) + "\n"
    if "--check" in sys.argv:
        current = out.read_text(encoding="utf-8") if out.exists() else ""
        if current != new:
            print(f"\nFAIL {out.name} is stale - regenerate it (run without --check)")
            return 1
        print(f"\n{out.name} is up to date")
        return 0

    out.write_text(new, encoding="utf-8")
    print(f"\nALL CHECKS PASSED - vectors written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
