"""Generate KKpsk2 test vectors for the Kotlin NoiseSession port (issue #193).

Runs `noiseprotocol` - the library aiosendspin 9.1.0 depends on - as BOTH the
initiator (server) and the responder (client), with every ephemeral pinned, and
dumps a complete transcript per cipher suite. The Kotlin responder is then
tested by replaying the initiator's side of these transcripts.

Both Sendspin suites are covered, because they differ in two ways that are easy
to get wrong and produce identical-looking failures:

  * `Noise_KKpsk2_25519_AESGCM_SHA256` is exactly 32 bytes, so its protocol name
    is used as `h` verbatim; `Noise_KKpsk2_25519_ChaChaPoly_SHA256` is 36 bytes
    and is hashed. Off-by-one on that boundary breaks only one suite.
  * ChaChaPoly's nonce counter is little-endian, AES-GCM's is big-endian.

Transport frames are emitted at n=0 AND n=1 in both directions: at n=0 the nonce
is twelve zero bytes under either endianness, so a counter bug is invisible.

Writes kotlin-vectors.json next to this script.
"""

from __future__ import annotations

import json
from pathlib import Path

from noise.connection import Keypair, NoiseConnection

HERE = Path(__file__).parent
OUT = HERE / "kotlin-vectors.json"

SUITES = [
    "Noise_KKpsk2_25519_ChaChaPoly_SHA256",
    "Noise_KKpsk2_25519_AESGCM_SHA256",
]

# Distinct, structured byte patterns so a mix-up is obvious in a failure diff.
SERVER_STATIC = bytes(range(0x20, 0x40))
SERVER_EPHEMERAL = bytes(range(0x80, 0xA0))
CLIENT_STATIC = bytes(range(0x60, 0x80))
CLIENT_EPHEMERAL = bytes(range(0xC0, 0xE0))
PSK = bytes(range(0xA0, 0xC0))

# Stand-ins for the real init frames. What matters for the test is that the
# prologue is an opaque byte string the implementation must not re-encode.
PROLOGUE = (
    b'{"type":"client/init","payload":{"client_id":"aaa","version":1,'
    b'"suite":"25519_ChaChaPoly_SHA256"}}'
    b'{"type":"server/init","payload":{"server_id":"bbb","version":1}}'
)
MSG1_PAYLOAD = json.dumps(
    {"psk_id": "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"}
).encode()
MSG2_PAYLOAD = b"{}"


def x25519_pub(private: bytes) -> bytes:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw)


def build(protocol: str, *, initiator: bool) -> NoiseConnection:
    n = NoiseConnection.from_name(protocol.encode())
    if initiator:
        n.set_as_initiator()
        static, ephemeral, remote = SERVER_STATIC, SERVER_EPHEMERAL, CLIENT_STATIC
    else:
        n.set_as_responder()
        static, ephemeral, remote = CLIENT_STATIC, CLIENT_EPHEMERAL, SERVER_STATIC
    n.set_prologue(PROLOGUE)
    n.set_keypair_from_private_bytes(Keypair.STATIC, static)
    n.set_keypair_from_private_bytes(Keypair.EPHEMERAL, ephemeral)
    n.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, x25519_pub(remote))
    n.set_psks(PSK)
    n.start_handshake()
    return n


def transcript(protocol: str) -> dict:
    ini = build(protocol, initiator=True)
    res = build(protocol, initiator=False)

    msg1 = ini.write_message(MSG1_PAYLOAD)
    got1 = res.read_message(msg1)
    assert got1 == MSG1_PAYLOAD, f"{protocol}: message 1 payload round-trip failed"

    msg2 = res.write_message(MSG2_PAYLOAD)
    got2 = ini.read_message(msg2)
    assert got2 == MSG2_PAYLOAD, f"{protocol}: message 2 payload round-trip failed"
    assert ini.handshake_finished and res.handshake_finished

    h_ini = ini.get_handshake_hash()
    h_res = res.get_handshake_hash()
    assert h_ini == h_res, f"{protocol}: handshake hashes disagree"

    # Transport, two frames each way. The responder's decrypt key is the
    # initiator's encrypt key; asserting both directions catches a swapped split.
    i2r = [ini.encrypt(b"i2r-0"), ini.encrypt(b"i2r-1")]
    for i, ct in enumerate(i2r):
        assert res.decrypt(ct) == f"i2r-{i}".encode()
    r2i = [res.encrypt(b"r2i-0"), res.encrypt(b"r2i-1")]
    for i, ct in enumerate(r2i):
        assert ini.decrypt(ct) == f"r2i-{i}".encode()

    # The responder's cipher states: decrypt handles initiator->responder.
    res_recv = res.noise_protocol.cipher_state_decrypt.k
    res_send = res.noise_protocol.cipher_state_encrypt.k

    return {
        "protocol": protocol,
        "protocol_name_len": len(protocol),
        "protocol_name_is_hashed": len(protocol) > 32,
        "prologue": PROLOGUE.hex(),
        "psk": PSK.hex(),
        "server_static_private": SERVER_STATIC.hex(),
        "server_static_public": x25519_pub(SERVER_STATIC).hex(),
        "server_ephemeral_private": SERVER_EPHEMERAL.hex(),
        "client_static_private": CLIENT_STATIC.hex(),
        "client_static_public": x25519_pub(CLIENT_STATIC).hex(),
        "client_ephemeral_private": CLIENT_EPHEMERAL.hex(),
        "message_1": msg1.hex(),
        "message_1_payload_utf8": MSG1_PAYLOAD.decode(),
        "message_2": msg2.hex(),
        "message_2_payload_utf8": MSG2_PAYLOAD.decode(),
        "handshake_hash": h_res.hex(),
        "transport_key_recv": res_recv.hex(),
        "transport_key_send": res_send.hex(),
        "transport_i2r": [ct.hex() for ct in i2r],
        "transport_i2r_plaintext_utf8": [f"i2r-{i}" for i in range(2)],
        "transport_r2i": [ct.hex() for ct in r2i],
        "transport_r2i_plaintext_utf8": [f"r2i-{i}" for i in range(2)],
    }


# commonTest so both androidHostTest (JVM) and androidDeviceTest (on-device,
# ART/arm64) can drive the same vectors.
KOTLIN_OUT = (
    HERE.parents[2] / "android" / "shared" / "src" / "commonTest" / "kotlin"
    / "com" / "sendspindroid" / "sendspin" / "crypto" / "NoiseTestVectors.kt"
)

KOTLIN_HEADER = '''package com.sendspindroid.sendspin.crypto

// GENERATED FILE - do not edit by hand.
// Regenerate with: python ci/conformance/noise/make_kotlin_vectors.py
//
// Produced by `noiseprotocol` (the library aiosendspin 9.1.0 depends on) acting
// as BOTH parties with every ephemeral pinned, so these are reference vectors
// rather than a recording of our own output.
//
// In Sendspin the SERVER is the Noise initiator and the CLIENT is the responder.
// Everything here is written from the responder's (our) point of view:
// `transportKeyRecv` decrypts `transportI2r`.

/** One complete KKpsk2 transcript for a single cipher suite. */
data class NoiseVector(
    val protocol: String,
    val protocolNameIsHashed: Boolean,
    val prologue: String,
    val psk: String,
    val serverStaticPublic: String,
    val clientStaticPrivate: String,
    val clientStaticPublic: String,
    val clientEphemeralPrivate: String,
    val message1: String,
    val message1PayloadUtf8: String,
    val message2: String,
    val message2PayloadUtf8: String,
    val handshakeHash: String,
    val transportKeyRecv: String,
    val transportKeySend: String,
    val transportI2r: List<String>,
    val transportI2rPlaintextUtf8: List<String>,
    val transportR2i: List<String>,
    val transportR2iPlaintextUtf8: List<String>,
)

object NoiseTestVectors {
'''


def kotlin_literal(v: dict, name: str) -> str:
    def s(key: str) -> str:
        return f'"{v[key]}"'

    def lst(key: str) -> str:
        return "listOf(" + ", ".join(f'"{x}"' for x in v[key]) + ")"

    return f"""    val {name} = NoiseVector(
        protocol = {s('protocol')},
        protocolNameIsHashed = {str(v['protocol_name_is_hashed']).lower()},
        prologue = {s('prologue')},
        psk = {s('psk')},
        serverStaticPublic = {s('server_static_public')},
        clientStaticPrivate = {s('client_static_private')},
        clientStaticPublic = {s('client_static_public')},
        clientEphemeralPrivate = {s('client_ephemeral_private')},
        message1 = {s('message_1')},
        message1PayloadUtf8 = {json.dumps(v['message_1_payload_utf8'])},
        message2 = {s('message_2')},
        message2PayloadUtf8 = {json.dumps(v['message_2_payload_utf8'])},
        handshakeHash = {s('handshake_hash')},
        transportKeyRecv = {s('transport_key_recv')},
        transportKeySend = {s('transport_key_send')},
        transportI2r = {lst('transport_i2r')},
        transportI2rPlaintextUtf8 = {lst('transport_i2r_plaintext_utf8')},
        transportR2i = {lst('transport_r2i')},
        transportR2iPlaintextUtf8 = {lst('transport_r2i_plaintext_utf8')},
    )
"""


def main() -> int:
    suites = [transcript(p) for p in SUITES]
    out = {
        "_generated_by": (
            "ci/conformance/noise/make_kotlin_vectors.py - produced by "
            "noiseprotocol acting as both parties, every ephemeral pinned"
        ),
        "_note_responder_is_the_client": (
            "In Sendspin the SERVER is the Noise initiator and the CLIENT is the "
            "responder. These vectors are written from the responder's point of "
            "view: transport_key_recv decrypts transport_i2r."
        ),
        "suites": suites,
    }
    OUT.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")

    names = {
        "Noise_KKpsk2_25519_ChaChaPoly_SHA256": "chaChaPoly",
        "Noise_KKpsk2_25519_AESGCM_SHA256": "aesGcm",
    }
    body = KOTLIN_HEADER
    for s in suites:
        body += kotlin_literal(s, names[s["protocol"]]) + "\n"
    body += "    val all = listOf(" + ", ".join(names[s["protocol"]] for s in suites) + ")\n}\n"
    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text(body, encoding="utf-8")

    for s in suites:
        print(f"PASS {s['protocol']}  (name {s['protocol_name_len']} bytes, "
              f"hashed={s['protocol_name_is_hashed']})")
    print(f"\nwrote {OUT}")
    print(f"wrote {KOTLIN_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
