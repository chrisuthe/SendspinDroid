"""Generate a full cleartext-handshake transcript for the Kotlin wire layer (#195).

Where make_kotlin_vectors.py tests the Noise driver in isolation, this exercises
the layer above it: the exact `client/init` and `server/init` frames, and the
prologue built from their raw bytes.

That prologue is the highest-risk part of the migration. The spec requires
hashing the bytes "exactly as sent and received, not a re-encoding of the parsed
message", and a client that re-serialises `server/init` will pass every unit test
on the parsed object while failing against every real server, with no error
message on either side. Driving the Kotlin driver with these literal frames is
what catches that.

`noiseprotocol` plays the server (the Noise initiator), with both ephemerals
pinned so the transcript is reproducible.

Writes wire-vectors.json plus a Kotlin constants file.
"""

from __future__ import annotations

import json
from pathlib import Path

from noise.connection import Keypair, NoiseConnection

HERE = Path(__file__).parent
OUT = HERE / "wire-vectors.json"
KOTLIN_OUT = (
    HERE.parents[2] / "android" / "shared" / "src" / "commonTest" / "kotlin"
    / "com" / "sendspindroid" / "sendspin" / "protocol" / "WireTestVectors.kt"
)

PROTOCOL = "Noise_KKpsk2_25519_ChaChaPoly_SHA256"
SUITE_WIRE = "25519_ChaChaPoly_SHA256"

SERVER_STATIC = bytes(range(0x20, 0x40))
SERVER_EPHEMERAL = bytes(range(0x80, 0xA0))
CLIENT_STATIC = bytes(range(0x60, 0x80))
CLIENT_EPHEMERAL = bytes(range(0xC0, 0xE0))
PSK = bytes(range(0xA0, 0xC0))


def b64u(raw: bytes) -> str:
    import base64
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def x25519_pub(private: bytes) -> bytes:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw)


def psk_id_for(psk: bytes) -> str:
    import hashlib
    return b64u(hashlib.sha256(b"sendspin-psk-id-v1" + psk).digest())


# These must be byte-identical to what the Kotlin builders emit. kotlinx's
# buildJsonObject preserves insertion order and renders compactly, so the field
# order here mirrors InitMessages.buildClientInit exactly. The Kotlin test
# asserts our builder reproduces CLIENT_INIT, which is what keeps the two in
# sync - if kotlinx ever changes its rendering, that assertion fails loudly
# rather than the prologue silently diverging.
CLIENT_ID = b64u(x25519_pub(CLIENT_STATIC))
SERVER_ID = b64u(x25519_pub(SERVER_STATIC))

CLIENT_INIT = (
    '{"type":"client/init","payload":'
    f'{{"client_id":"{CLIENT_ID}","version":1,"suite":"{SUITE_WIRE}"}}}}'
)
# Deliberately NOT in the same field order as the client's, and with a key the
# client must ignore: a driver that re-encodes this to build the prologue will
# reorder the fields and drop the unknown one, and the handshake will fail.
SERVER_INIT = (
    '{"type":"server/init","payload":'
    f'{{"version":1,"server_id":"{SERVER_ID}","future_field":"ignore me"}}}}'
)


def main() -> int:
    prologue = CLIENT_INIT.encode() + SERVER_INIT.encode()

    def build(initiator: bool) -> NoiseConnection:
        n = NoiseConnection.from_name(PROTOCOL.encode())
        if initiator:
            n.set_as_initiator()
            static, eph, remote = SERVER_STATIC, SERVER_EPHEMERAL, CLIENT_STATIC
        else:
            n.set_as_responder()
            static, eph, remote = CLIENT_STATIC, CLIENT_EPHEMERAL, SERVER_STATIC
        n.set_prologue(prologue)
        n.set_keypair_from_private_bytes(Keypair.STATIC, static)
        n.set_keypair_from_private_bytes(Keypair.EPHEMERAL, eph)
        n.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, x25519_pub(remote))
        n.set_psks(PSK)
        n.start_handshake()
        return n

    server, client = build(True), build(False)

    msg1_payload = json.dumps({"psk_id": psk_id_for(PSK)}).encode()
    msg1 = server.write_message(msg1_payload)
    assert client.read_message(msg1) == msg1_payload

    msg2 = client.write_message(b"{}")
    assert server.read_message(msg2) == b"{}"
    assert server.handshake_finished and client.handshake_finished
    assert server.get_handshake_hash() == client.get_handshake_hash()

    # One application message each way, through the encrypted channel, with the
    # [type][body] plaintext layout the wire codec adds.
    hello = b'\x00{"type":"client/hello"}'
    hello_ct = client.encrypt(hello)
    assert server.decrypt(hello_ct) == hello
    state = b'\x00{"type":"server/hello","payload":{"name":"Dev"}}'
    state_ct = server.encrypt(state)
    assert client.decrypt(state_ct) == state

    v = {
        "_generated_by": "ci/conformance/noise/make_wire_vectors.py",
        "suite_wire_name": SUITE_WIRE,
        "client_static_private": CLIENT_STATIC.hex(),
        "client_id": CLIENT_ID,
        "server_id": SERVER_ID,
        "psk": PSK.hex(),
        "psk_id": psk_id_for(PSK),
        "client_ephemeral_private": CLIENT_EPHEMERAL.hex(),
        "client_init_frame": CLIENT_INIT,
        "server_init_frame": SERVER_INIT,
        "prologue_hex": prologue.hex(),
        "noise_handshake_1_frame": json.dumps(
            {"type": "noise/handshake", "payload": {"data": b64u(msg1)}},
            separators=(",", ":"),
        ),
        "noise_message_2_b64u": b64u(msg2),
        "handshake_hash": client.get_handshake_hash().hex(),
        "app_frame_client_to_server": hello_ct.hex(),
        "app_frame_client_to_server_plaintext": hello.decode(),
        "app_frame_server_to_client": state_ct.hex(),
        "app_frame_server_to_client_plaintext": state.decode(),
    }
    OUT.write_text(json.dumps(v, indent=2) + "\n", encoding="utf-8")

    def kt(s: str) -> str:
        return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'

    body = f'''package com.sendspindroid.sendspin.protocol

// GENERATED FILE - do not edit by hand.
// Regenerate with: python ci/conformance/noise/make_wire_vectors.py
//
// A complete cleartext handshake transcript produced by `noiseprotocol` acting
// as the server, with both ephemerals pinned.
//
// serverInitFrame deliberately orders its fields differently from the client's
// and carries an unknown key. A driver that re-encodes it to build the prologue
// will normalise both away and the handshake will fail - which is the point.
object WireTestVectors {{
    const val suiteWireName = {kt(SUITE_WIRE)}
    const val clientStaticPrivate = {kt(CLIENT_STATIC.hex())}
    const val clientId = {kt(CLIENT_ID)}
    const val serverId = {kt(SERVER_ID)}
    const val psk = {kt(PSK.hex())}
    const val pskId = {kt(psk_id_for(PSK))}
    const val clientEphemeralPrivate = {kt(CLIENT_EPHEMERAL.hex())}
    const val clientInitFrame = {kt(CLIENT_INIT)}
    const val serverInitFrame = {kt(SERVER_INIT)}
    const val prologueHex = {kt(prologue.hex())}
    const val noiseHandshake1Frame = {kt(v["noise_handshake_1_frame"])}
    const val noiseMessage2B64u = {kt(v["noise_message_2_b64u"])}
    const val handshakeHash = {kt(v["handshake_hash"])}
    const val appFrameClientToServer = {kt(hello_ct.hex())}
    const val appFrameClientToServerPlaintext = {kt(hello.decode())}
    const val appFrameServerToClient = {kt(state_ct.hex())}
    const val appFrameServerToClientPlaintext = {kt(state.decode())}
}}
'''
    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text(body, encoding="utf-8")
    print(f"PASS full handshake transcript ({PROTOCOL})")
    print(f"wrote {OUT}")
    print(f"wrote {KOTLIN_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
