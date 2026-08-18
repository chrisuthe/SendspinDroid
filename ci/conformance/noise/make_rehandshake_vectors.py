"""Generate golden vectors for the in-band re-handshake (item 2.6, #223).

`connection.md#re-handshake`: "`client/init` and `server/init` are not re-sent -
`client_id`, `server_id`, and `suite` carry over. The new handshake's prologue
is the prior handshake's hash `h`."

That one sentence is the whole risk. A re-handshake that used the *initial*
prologue, or the base64url text of `h` rather than its raw 32 bytes, fails at
message-1 decryption with an AEAD error several steps removed from the cause -
and the spec's response to any handshake failure is a silent socket close. So
the vector this produces exists to be asserted against, and the negative cases
matter as much as the positive one.

Both sides are the reference implementation (`noiseprotocol`, which aiosendspin
9.1.0 depends on). The Kotlin responder is the consumer of these vectors, not a
participant in generating them.

Writes ci/conformance/noise/rehandshake-vectors.json only if every check passes.
"""

import json
from pathlib import Path

from noise.connection import Keypair, NoiseConnection

HERE = Path(__file__).parent
PROTO = b"Noise_KKpsk2_25519_ChaChaPoly_SHA256"

# The initial handshake's prologue. Its only role here is to produce a realistic
# prior `h`; the re-handshake must NOT reuse it.
INITIAL_PROLOGUE = b"sendspin-rehandshake-initial-prologue-v1"

SERVER_STATIC = bytes(range(0x20, 0x40))
CLIENT_STATIC = bytes(range(0x60, 0x80))

# Handshake 1 ephemerals.
SERVER_EPHEMERAL_1 = bytes(range(0x80, 0xA0))
CLIENT_EPHEMERAL_1 = bytes(range(0xC0, 0xE0))

# Handshake 2 (the re-handshake) ephemerals - distinct, so a vector that
# accidentally replays handshake 1 is obvious.
SERVER_EPHEMERAL_2 = bytes(range(0x01, 0x21))
CLIENT_EPHEMERAL_2 = bytes(range(0x40, 0x60))

# The Sentinel-ish PSK for handshake 1, and the "newly delivered long_term_psk"
# the re-handshake promotes to. Different values, because promoting the trust
# level is the whole point of the exchange.
PSK_1 = bytes(range(0xA0, 0xC0))
PSK_2 = bytes([(b + 0x11) & 0xFF for b in range(0xA0, 0xC0)])

MSG1_PAYLOAD_1 = json.dumps({"psk_id": "initial-psk-id-placeholder"}).encode()
MSG1_PAYLOAD_2 = json.dumps({"psk_id": "rehandshake-psk-id-placeholder"}).encode()
MSG2_PAYLOAD = b"{}"


def x25519_pub(private: bytes) -> bytes:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw)


def build(role: str, prologue: bytes, psk: bytes, ephemeral: bytes) -> NoiseConnection:
    """The server is the initiator in both the initial handshake and the re-handshake."""
    n = NoiseConnection.from_name(PROTO)
    if role == "server":
        n.set_as_initiator()
        n.set_keypair_from_private_bytes(Keypair.STATIC, SERVER_STATIC)
        n.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, x25519_pub(CLIENT_STATIC))
    else:
        n.set_as_responder()
        n.set_keypair_from_private_bytes(Keypair.STATIC, CLIENT_STATIC)
        n.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, x25519_pub(SERVER_STATIC))
    n.set_prologue(prologue)
    n.set_keypair_from_private_bytes(Keypair.EPHEMERAL, ephemeral)
    n.set_psks(psk)
    n.start_handshake()
    return n


def run(prologue: bytes, psk: bytes, se: bytes, ce: bytes, msg1_payload: bytes) -> dict:
    """One complete KKpsk2 exchange. Returns the wire bytes and resulting state."""
    server = build("server", prologue, psk, se)
    client = build("client", prologue, psk, ce)

    msg1 = server.write_message(msg1_payload)
    got_payload1 = client.read_message(msg1)
    assert got_payload1 == msg1_payload, "message 1 payload round trip"

    msg2 = client.write_message(MSG2_PAYLOAD)
    got_payload2 = server.read_message(msg2)
    assert got_payload2 == MSG2_PAYLOAD, "message 2 payload must be the literal {}"

    assert client.handshake_finished and server.handshake_finished
    assert client.get_handshake_hash() == server.get_handshake_hash(), "h must agree"

    return {
        "prologue": prologue.hex(),
        "psk": psk.hex(),
        "server_ephemeral_private": se.hex(),
        "client_ephemeral_private": ce.hex(),
        "message_1": msg1.hex(),
        "message_1_payload": msg1_payload.decode(),
        "message_2": msg2.hex(),
        "handshake_hash": client.get_handshake_hash().hex(),
    }


def main() -> int:
    # Handshake 1: an ordinary session. Its `h` becomes the next prologue.
    first = run(INITIAL_PROLOGUE, PSK_1, SERVER_EPHEMERAL_1, CLIENT_EPHEMERAL_1, MSG1_PAYLOAD_1)
    prior_h = bytes.fromhex(first["handshake_hash"])
    assert len(prior_h) == 32, "h is a 32-byte SHA-256 output"

    # Handshake 2: prologue is the RAW 32 bytes of the prior h.
    second = run(prior_h, PSK_2, SERVER_EPHEMERAL_2, CLIENT_EPHEMERAL_2, MSG1_PAYLOAD_2)

    # The chained case: a third handshake's prologue is the second's h, proving
    # the rule composes rather than being special-cased to "the first one".
    third = run(
        bytes.fromhex(second["handshake_hash"]), PSK_2,
        SERVER_EPHEMERAL_1, CLIENT_EPHEMERAL_1, MSG1_PAYLOAD_2,
    )

    checks = [
        ("re-handshake h differs from the prior h",
         second["handshake_hash"] != first["handshake_hash"], True),
        ("chained h differs again",
         third["handshake_hash"] != second["handshake_hash"], True),
        ("re-handshake message 1 differs from the initial one",
         second["message_1"] != first["message_1"], True),
    ]

    # The negative case, and the reason this file exists: the same inputs with
    # the INITIAL prologue must fail. If this ever passes, the prologue is not
    # actually binding the two handshakes together.
    try:
        run(INITIAL_PROLOGUE, PSK_2, SERVER_EPHEMERAL_2, CLIENT_EPHEMERAL_2, MSG1_PAYLOAD_2)
        wrong_prologue_h = None
    except Exception:
        wrong_prologue_h = "failed-as-expected"
    # Both sides used the same wrong prologue, so they still agree with each
    # other - the point is that the resulting h is NOT the re-handshake's h,
    # so a client using the wrong prologue cannot produce the recorded message 2.
    mismatched = run(
        INITIAL_PROLOGUE, PSK_2, SERVER_EPHEMERAL_2, CLIENT_EPHEMERAL_2, MSG1_PAYLOAD_2,
    )
    checks.append((
        "the initial prologue yields a different message 2 than prior-h does",
        mismatched["message_2"] != second["message_2"], True,
    ))
    checks.append((
        "the initial prologue yields a different h than prior-h does",
        mismatched["handshake_hash"] != second["handshake_hash"], True,
    ))

    failed = [(name, got, want) for name, got, want in checks if got != want]
    for name, got, want in checks:
        print(f"{'ok  ' if (name, got, want) not in failed else 'FAIL'}  {name}")
    if failed:
        return 1

    out = {
        "_generated_by": "make_rehandshake_vectors.py",
        "_note": (
            "The re-handshake's prologue is the RAW 32 bytes of the prior "
            "handshake's h - not its base64url text, and not the initial "
            "handshake's prologue. wrong_prologue_* record what the wrong "
            "choice produces, so a test can assert we do not produce it."
        ),
        "protocol": PROTO.decode(),
        "server_static_private": SERVER_STATIC.hex(),
        "server_static_public": x25519_pub(SERVER_STATIC).hex(),
        "client_static_private": CLIENT_STATIC.hex(),
        "client_static_public": x25519_pub(CLIENT_STATIC).hex(),
        "initial": first,
        "rehandshake": second,
        "chained": third,
        "wrong_prologue_message_2": mismatched["message_2"],
        "wrong_prologue_handshake_hash": mismatched["handshake_hash"],
    }
    path = HERE / "rehandshake-vectors.json"
    path.write_text(json.dumps(out, indent=2) + "\n")
    print(f"\nwrote {path}")
    print(f"  prior h      : {first['handshake_hash'][:16]}...")
    print(f"  re-handshake h: {second['handshake_hash'][:16]}...")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
