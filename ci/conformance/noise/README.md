# Noise KKpsk2 spike and reference prototype

Deliverables for audit item 0.1 (issue #189). Two things live here:

1. **The spike result** that settles audit decision D3 (`NoiseSpike.java`).
2. **A working, reference-validated `KKpsk2` responder** (`KKpsk2Responder.java`)
   plus golden vectors (`vectors.json`), so item 1.2 (issue #193) is a port
   rather than a design task.

## Result: hand-roll, do not adopt noise-java

`org.signal.forks:noise-java:0.1.1` fails the two questions that matter. Run
`NoiseSpike.java` to reproduce:

| Question | Result |
|---|---|
| Q1 construct `Noise_KKpsk2_25519_ChaChaPoly_SHA256` | **FAIL** - `IllegalArgumentException: Handshake pattern is not recognized` (same for the AESGCM suite) |
| Q1 control: `Noise_KK_25519_ChaChaPoly_SHA256` | OK - so the failure is the `psk2` modifier specifically, not the harness |
| Q2 expose the handshake hash `h` | OK - `getHandshakeHash()` returns 32 bytes, equal on both sides; `setPrologue` accepts an arbitrary prologue |
| Q3 supply the PSK *after* Noise message 1 | **FAIL** - `IllegalStateException: Handshake has already started; cannot set pre-shared key` |
| Q4 both Sendspin cipher suites | OK - `ChaChaPolyCipherState`, `AESGCMOnCtrCipherState` |

Q1 and Q3 are both load-bearing. Sendspin needs `psk2` (the PSK is mixed at the
end of message 2) *and* needs to read `psk_id` from message 1 before choosing
which PSK to mix - see `connection.md#pre-shared-key`. A library that demands the
PSK up front cannot express that. No other JVM Noise artifact on Maven Central
implements `psk` modifiers.

**Decision: hand-roll the state machine on BouncyCastle.** The primitive surface
is small: X25519, SHA-256, HMAC-SHA256, ChaCha20-Poly1305, AES-GCM.

## The prototype

`KKpsk2Responder.java` is a complete, working responder validated against
`noiseprotocol` - the same library `aiosendspin` 9.1.0 depends on, so agreement
here means agreement with what Music Assistant actually runs.

```
KKpsk2:
  -> s
  <- s
  ...
  -> e, es, ss
  <- e, ee, se, psk
```

In Sendspin the **server is the Noise initiator** and the **client is the
responder**, regardless of who opened the WebSocket. This class is the client
side.

### Two details that cost real debugging time

**The `e` token also calls `MixKey` in PSK handshakes.** In a plain pattern `e`
only does `MixHash(e.public_key)`. Under any `psk` modifier it does
`MixHash` *and* `MixKey` on the same public key. Omitting it diverges the
symmetric state at the very first token, and the only symptom is an AEAD tag
failure several steps later. Both `e` sites in the prototype carry a comment.

**Noise's HKDF is not RFC 5869 applied naively.** Each output is an HMAC over the
previous output concatenated with a counter byte, and `MixKey` takes two outputs
while `MixKeyAndHash` takes three. Reaching for a generic HKDF helper produces a
handshake that passes self-tests and fails against a real peer.

## Reproducing

Needs `JAVA_HOME` set and a Python env with `noiseprotocol` and `cryptography`
(both come with `pip install 'aiosendspin[server]==9.1.0'`).

```bash
cd ci/conformance/noise
curl -sL -o noise-java.jar https://repo1.maven.org/maven2/org/signal/forks/noise-java/0.1.1/noise-java-0.1.1.jar
curl -sL -o bcprov.jar     https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.80/bcprov-jdk18on-1.80.jar

# The library spike (Q1-Q4)
"$JAVA_HOME/bin/javac" -cp noise-java.jar -d . NoiseSpike.java
"$JAVA_HOME/bin/java"  -cp "noise-java.jar;." NoiseSpike

# The prototype: live interop over loopback TCP
"$JAVA_HOME/bin/javac" -cp bcprov.jar -d . KKpsk2Responder.java
python peer.py

# Regenerate and re-validate the golden vectors
python make_vectors.py
```

`peer.py` asserts both sides derive the same handshake hash and that transport
messages round-trip in both directions. `make_vectors.py` pins both ephemerals,
drives the exchange through the reference implementation, and only writes
`vectors.json` if every field round-trips.

## vectors.json

Deterministic vectors for the Kotlin port's tests (issue #193). Every value has
been round-tripped through `noiseprotocol`, not merely produced by this
prototype. Fields are hex unless named `_utf8`.

The Kotlin `NoiseSession` tests should assert at minimum:

- `message_1` decrypts to `message_1_payload_utf8` **without the PSK mixed in**
  (that is the whole point of `psk2`, and it is what lets `psk_id` selection work)
- `h_after_message_1` matches after reading message 1
- writing message 2 with `client_ephemeral_private` pinned reproduces `message_2`
  byte for byte
- `handshake_hash` matches after the handshake completes
- the transport keys are not swapped - `transport_probe_ciphertext` must decrypt
  under `transport_key_recv` to `transport_probe_plaintext_utf8`

A negative test is worth adding too: substituting a different prologue must make
message 1 fail to decrypt. Prologue handling is the highest-risk part of #193.

## Not covered here

- The AESGCM suite is constructible but the prototype only implements
  ChaChaPoly. #193 must add it and generate a second vector set.
- No Android device run. R8 can strip BouncyCastle classes reached only
  reflectively; the prototype uses the low-level `org.bouncycastle.crypto.*` API
  precisely to avoid JCA provider registration, which conflicts with Android's
  repackaged `com.android.org.bouncycastle`. Verify APK size and a release build
  in #193.
- `noise-java.jar` and `bcprov.jar` are deliberately not committed; the commands
  above fetch them.
