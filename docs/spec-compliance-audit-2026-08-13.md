# Sendspin Spec Compliance Audit and Migration Plan - 2026-08-13

Audit of SendSpinDroid 2.0.0-Beta14 (`c5899b0`) against the Sendspin spec at
`github.com/Sendspin/spec@4599494` (2026-08-12).

Supersedes `docs/spec-compliance-audit-2026-06-12.md`. That audit was written
against the 2026-06-11 spec. Everything below the "Core messages" line has since
been restructured: the spec added a mandatory end-to-end encryption layer, a
pairing subsystem, a management subsystem, and a new activation handshake. Some
fixes recommended in the June audit are now themselves obsolete (see
[Obsoleted by spec churn](#obsoleted-by-spec-churn)).

---

## 1. Executive summary

**SendSpinDroid does not implement the current Sendspin handshake at all.** It
speaks the pre-encryption dialect: it opens a plain `ws://` connection and sends
`client/hello` as its first frame in a WebSocket text frame. The current spec
requires `client/init` -> `server/init` -> two `noise/handshake` messages, after
which *every* application message becomes a Noise transport ciphertext carried in
a WebSocket **binary** frame.

The app still connects today only because Music Assistant's Sendspin provider
ships a compatibility shim. MA's `allow_legacy_clients` config entry defaults to
`true` and maps to `aiosendspin`'s `allow_unencrypted` / `allow_noncompliant_clients`
server flags. MA's own description of that toggle:

> "Accept legacy Sendspin clients that don't implement the current specification,
> including clients that connect without encryption, so their traffic can be
> intercepted on the local network. Disable this to accept only spec-compliant
> clients and to stop holding back newer protocol behavior. **This option is
> temporary and will be removed in a future release.**"

So this is not a feature backlog. It is a deprecation deadline with an unknown
but finite date, and the work behind it (Noise handshake, identity keypair,
pairing, trust storage) is measured in weeks, not days. Everything else in this
document is secondary to Phase 1.

**Three findings shape the plan:**

1. **The official JVM library does not help.** `Sendspin/sendspin-jvm` v0.3.3
   (2026-08-02) has no Noise, pairing, management, or crypto code at all - its
   entire source tree is 16 Kotlin files with no crypto surface. The June audit's
   "revisit the library at ~v1.0" recommendation stands, but the library is now
   *further* behind the spec than we are in some respects. It cannot be the route
   to encryption. We build it.

2. **Only one pairing method is mandatory for clients, and it needs no PAKE.**
   The spec requires servers to implement all three methods but clients only
   `pairing_psk`: "Servers must implement all three; clients must implement
   Pairing PSK and may additionally implement either or both PIN methods."
   The Pairing PSK flow has no CPace round, no PIN, no commit/reveal - the Noise
   handshake authenticates via the Pairing PSK and the client immediately sends
   `client/pair-finalize` with the new long-term PSK in the clear (inside the
   already-encrypted channel). **This removes CPace-X25519-SHA512 from the
   critical path entirely** and cuts the Phase 1 crypto surface to X25519 + HKDF +
   SHA-256 + ChaCha20-Poly1305/AES-GCM, all of which BouncyCastle already provides.

3. **The sync-quality bar tightened and we are now measurably outside it.** The
   spec added hard numeric limits for `player@v1` (2026-06-30, "Define minimum
   sync standards"). We exceed the speed cap by 4x and the steady-state accuracy
   floor by 10x. This is independent of the encryption work and can proceed in
   parallel.

---

## 2. Ecosystem state

| Component | Version / date | Encryption + pairing? |
|---|---|---|
| `Sendspin/spec` | `4599494`, 2026-08-12 | Normative. Mandatory since 2026-06-29 (#84) |
| `Sendspin/aiosendspin` | 9.1.0 | Yes - full `noise/` package: driver, keys, pairing, pairing_token, pin, session, trust_store, wire |
| `music-assistant/server` | 2.10.0.devs, 2.9.13 stable | Pins `aiosendspin[server]==9.1.0`. Has `security.py`, pairing UI, PIN length config. Legacy shim on by default, documented temporary |
| `Sendspin/sendspin-jvm` | v0.3.3, 2026-08-02 | **No.** No noise/pairing/crypto/management sources |
| `Sendspin/conformance` | 2026-07-18 | Already wired into our CI (`.github/workflows/conformance.yml`) |
| **SendSpinDroid** | 2.0.0-Beta14 | **No** |

The spec's relevant change history since our last audit:

| Date | Change |
|---|---|
| 2026-06-29 | **Add encryption support (#84)** - Noise KKpsk2, pairing, management, `server/activate` |
| 2026-06-29 | Remove alternative connection methods |
| 2026-06-30 | Define minimum sync standards for `player@v1` (#104) |
| 2026-06-30 | Specify the volume to amplitude curve (#109); avoid audible clicks (#110) |
| 2026-06-30 | Add `source` role (#105); `server_transmitted` on all stream messages (#106) |
| 2026-06-30 | Require clients to ignore unrecognized fields (#108) |
| 2026-07-07 | **Rename client `state` to a boolean `available` (#115)** |
| 2026-07-13 | Wrap PSK under CPace output (#117); domain separator to pair commit (#118) |
| 2026-07-29 | Standardize the pairing token format (#125) |
| 2026-08-05 | PIN pairing window (#130); language hint for spoken PIN (#131) |

---

## 3. Gap catalog

Severity key:
**S1** blocks connection once the legacy shim is removed.
**S2** spec violation with user-visible or interop consequence.
**S3** spec violation, low practical impact today.
**S4** unimplemented optional role or feature.

### 3.1 Connection and encryption (S1)

| # | Gap | Current state | Evidence |
|---|---|---|---|
| C1 | No `client/init` / `server/init` | Message type constants do not exist; grep returns no hits | `SendSpinProtocol.kt:90-106` |
| C2 | No Noise handshake | No `noise/handshake` handling, no crypto dependency of any kind | Only crypto dep is `androidx.security:security-crypto` for `EncryptedSharedPreferences`, `app/build.gradle.kts:245` |
| C3 | `client_id` is a UUIDv4, not a Curve25519 public key | `UUID.randomUUID().toString()`, stored in plain SharedPreferences under `player_id` | `UserSettings.kt:207`, consumed `SendSpin.kt:229` |
| C4 | App messages sent as WebSocket **text** frames | Spec requires binary frames carrying Noise ciphertexts, with a leading message-type byte (`0` = JSON) | `BaseWebSocketTransport.kt:172-173`; no call site in `app/src/main` sends binary |
| C5 | Client speaks first | We send `client/hello` on socket open; spec has server send `server/hello` first, after the handshake | `SendSpin.kt:1328-1353`, `SendSpinProtocolHandler.kt:213-231` |
| C6 | No `server/activate` | Type does not exist anywhere. `active_roles` is read from the non-spec `server/hello` payload and only logged, never enforced | `MessageParser.kt:39-48`, `SendSpinProtocolHandler.kt:574` |
| C7 | `client/state` + `client/time` start on `server/hello` | Spec: nothing may be sent until the first `server/activate` arrives | `SendSpinProtocolHandler.kt:588-589` |
| C8 | No `trust_level` in `client/hello` | Field absent | `MessageBuilder.kt:28-77` |
| C9 | No `supported_pair_methods` / `unpaired_access` in `client/hello` | Fields absent; both are required | `MessageBuilder.kt:28-77` |
| C10 | No message fragmentation (types `2`/`3`) | Noise caps a transport message at 65535 bytes, so payload is capped at 65518. Artwork at 500x500 JPEG will usually fit, but PNG/BMP or larger art will not | `BinaryMessageParser.kt:122-137` handles only 4, 8-11, 16 |
| C11 | Non-spec `connection_reason` field parsed from `server/hello` | An aiosendspin legacy-mode invention; disappears with the shim | `MessageParser.kt:37` |

### 3.2 Pairing and management (S1 for pairing, S2 for management)

Entirely absent. Grep for `pair/`, `client/pair-`, `server/pair-`, `management/`,
`server/unpair`, `supported_pair_methods`, `trust_level` returns **no hits** in
main source.

Required client-side surface:

| Area | Required | Notes |
|---|---|---|
| Identity | Curve25519 static keypair, persisted | Public key base64url (43 chars) becomes `client_id` |
| PSK store | Long-term Sendspin PSK records keyed by `psk_id`, with optional bound `server_id` | Spec allows a simplified "shared-PSK model" storing no `server_id`; the "stored-pubkey model" is stronger and is what we should ship |
| Sentinel PSK | Published constant, used pre-pairing | `SHA-256("sendspin-sentinel-psk-v1")`, `psk_id` = `GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo` |
| Pairing PSK | Per-device CSPRNG value, persisted, never rotated by the client on its own | Must stay in the handshake PSK candidate set whenever the method is enabled - the server re-handshakes to it |
| Pairing token | `SP:` + version + base32 body of `client_key (32) || pairing_psk (32)`, `2`->`9` transliterated, 107 chars | Surface as text + QR in Settings. Reference vector in `pairing.md` |
| `client/pair-finalize` | Sends `long_term_psk` directly in the Pairing PSK flow | No wrapping, no PAKE |
| Re-handshake | Server reruns the Noise handshake in-band to promote to the new PSK, without closing the socket | Prologue is the prior handshake hash `h`; `client/init`/`server/init` are not re-sent |
| `server/unpair` | Remove record, send `client/goodbye` reason `unpaired`, close | Must NOT remove a shared-PSK record |
| `management/*` | `list-records`, `add-record`, `remove-record`, `get-pairing-config`, `set-pairing-config`, `open-pairing-window`, and the `management/result` reply | Gated on `'management'` in activities AND a long-term PSK match |

PIN methods (`dynamic_pin`, `static_pin`) are **optional for clients** and need
CPace-X25519-SHA512 (draft-irtf-cfrg-cpace-21), PSK wrapping, a persisted failure
counter with escalation at 10, and a pairing window. Deferred - see Phase 4.

### 3.3 Player role (S2)

| # | Gap | Spec | Current | Evidence |
|---|---|---|---|---|
| P1 | Speed correction cap | `+/-0.5%`, sliding 150 ms average | `MAX_SPEED_CORRECTION = 0.02` (`+/-2%`), 4x over | `SyncAudioPlayer.kt:277` |
| P2 | Steady-state sync accuracy | Within `+/-1 ms`, target `+/-0.5 ms` | Dead band is `DEADBAND_THRESHOLD_US = 10_000` (10 ms) - errors below 10 ms get zero correction, so steady-state error is bounded only by ~10 ms | `SyncAudioPlayer.kt:273`, applied `:2323-2327` |
| P3 | No one-shot resync in the 10 ms - 500 ms band | Spec wants a one-shot snap when error would exceed the 1 ms floor | The whole band is handled by continuous +/-2% correction; reanchor only fires above 500 ms | `SyncAudioPlayer.kt:2134-2139`, `:302` |
| P4 | `client/state` sends `state` string | Field renamed to `available: boolean` on 2026-07-07 (#115) | Sends `state: "synchronized" \| "error" \| "external_source"` | `MessageBuilder.kt:114`, `SendSpinProtocolHandler.kt:319-359` |
| P5 | `available: true` sent before clock sync | Spec: "A player MUST NOT report `available: true` until its time filter has converged" | First `client/state` fires on `server/hello`, before time sync starts, carrying `state:"error"` | `SendSpinProtocolHandler.kt:588-589` |
| P6 | Volume curve not applied | `amplitude = (volume/100)^1.5` | Strictly linear into `AudioManager.STREAM_MUSIC`; `SyncAudioPlayer.setVolume()` is a no-op | `PlaybackService.kt:2218-2231`, `SyncAudioPlayer.kt:838-844` |
| P7 | No volume ramp | "SHOULD apply volume changes over a short ramp" | `setStreamVolume(..., 0)` - instantaneous, no fade | `PlaybackService.kt:2229` |
| P8 | Volume and mute not independent | "a volume change MUST NOT clear the mute state" | Mute is implemented *as* volume 0; a volume change while muted physically unmutes | `PlaybackService.kt:1606-1621` |
| P9 | `muted` not persisted | "Persisting `volume` and `muted` across reboots is RECOMMENDED" | `currentMuted` defaults to `false` every launch; no `UserSettings` key | `SendSpinProtocolHandler.kt:39` |
| P10 | Late chunks not dropped during playback | "clients should drop these late chunks" | No past-timestamp check in `processChunk()`; dropping happens only on the start-gating paths | `SyncAudioPlayer.kt:1342-1442` |
| P11 | `stream/clear` `roles` array ignored | Should clear only the named roles | Dispatcher calls `handleStreamClear()` with no payload at all - an artwork-only clear wipes the audio pipeline | `SendSpinProtocolHandler.kt:557`, `:683-687` |
| P12 | `static_delay_ms` auto-measurement not persisted | "Clients must persist `static_delay_ms` locally across reboots and server reconnections" | Re-measured every session by `OutputLatencyEstimator`; only the user slider offset is persisted. No per-output-device map | `SyncAudioPlayer.kt:644-662`, `UserSettings.kt:235-245` |
| P13 | `required_lead_time_ms` is a fixed 500 | Spec recommends adapting it, and lowering it for `stream/clear` restarts | Static constant, never overridden | `SendSpinProtocol.kt:83` |
| P14 | Compressed codecs pinned to 16-bit | `bit_depth` is meaningful for `flac` | FLAC/Opus hard-coded to 16-bit, so 24-bit FLAC is never negotiated | `MessageBuilder.kt:234-240` |
| P15 | FLAC `codec_header` passed through unvalidated | Header is `fLaC` marker + STREAMINFO | Handed to MediaCodec as `csd-0` verbatim with no parsing or marker stripping | `FlacDecoder.kt:36-38` |
| P16 | Unknown codec silently falls back to PCM | - | `AudioDecoderFactory.create()` returns `PcmDecoder` for anything unrecognized, which would render compressed data as noise | `AudioDecoderFactory.kt:32-48` |

Note on P1-P3: these three interact. Tightening the dead band without also
tightening the cap will increase correction frequency; the pair should be tuned
together against the conformance harness, and the +/-0.5% cap makes the spec's
suggested `N = round(21us * sample_rate / 1e6)` step sizing directly applicable.

### 3.4 Non-player roles

| Role | Status | Detail |
|---|---|---|
| `metadata@v1` | Claimed, partially correct (S2) | `album_artist`, `year`, `track` are parsed but dropped before the UI - `Callback.onMetadataUpdate` has no parameters for them (`SendSpin.kt:128-136`). Progress extrapolation via the time filter **is** correctly implemented (`SendSpinProtocol.kt:184-194`) |
| `controller@v1` | Claimed, substantially incomplete (S2) | `seek` and `seek_relative` entirely missing; `seek_max_ms` not parsed. `stop`, `volume`, `mute`, `repeat_*`, `shuffle`/`unshuffle` are defined in `SendSpin.kt:900-922` but have **no callers** - `SendSpinPlayer.setRepeatMode`/`setShuffleModeEnabled`/`seekTo` are explicit no-ops. `controllerState` is populated but has **zero consumers**, so `supported_commands` never gates the UI |
| `artwork@v1` | Claimed, minimal (S2/S4) | 1 channel of a possible 4; `jpeg` only; `source: 'none'` unreachable; artwork `stream/start` object never parsed (`parseStreamStart` returns null without a `player` key); `stream/request-format` never sent for artwork; binary channel index is parsed then discarded at `SendSpin.kt:485-491`; timestamp parsed then dropped, art displayed immediately with no clock translation. Empty-payload clear **is** handled |
| `visualizer@v1` | Not claimed (S4) | Correct to not claim it. But note types 17-20 currently fall to `Unknown` - only type 16 is recognized, and it is discarded at `SendSpinProtocolHandler.kt:755-757` |
| `color@v1` | Not claimed (S4) | Confirmed absent. The app already extracts a palette locally from artwork (`MainActivity.kt:3063`), so this role is a quality upgrade, not new capability |
| `source@v1` | Not claimed (S4) | Confirmed absent, and correctly so - no `RECORD_AUDIO` permission, no outbound binary path. Note the spec forbids `source@v1` at trust level `none`, so it depends on pairing landing first |

### 3.5 Cross-cutting message semantics (S2)

| # | Gap | Detail |
|---|---|---|
| M1 | `server/state` null-clearing not implemented | Spec: a leaf set to `null` clears it; a whole role object set to `null` clears the role. `payload["metadata"] as? JsonObject` makes `JsonNull` indistinguishable from absent (`MessageParser.kt:75`), so `metadata: null` is read as "no update" |
| M2 | `server/state` shallow merge not implemented for metadata | `handleServerState` replaces `lastMetadata` wholesale (`SendSpinProtocolHandler.kt:604-607`), so a delta carrying only `progress` synthesizes empty title/artist/album. `optStringClean` also collapses absent, `null`, and the literal `"null"` to `""`, which downstream is treated as "clear" - so unchanged fields get wrongly cleared |
| M3 | `group/update` delta merge uses an empty-string sentinel | Absent and `""` are indistinguishable (`MessageParser.kt:179-187`), so a server legitimately clearing `group_name` cannot |
| M4 | `client/goodbye` covers 2 of 8 reasons | Only `restart` (`SendSpin.kt:863`) and `user_request` (`:888`) are ever sent. Missing: `another_server` (needed even today - switching servers is a UI action), `shutdown`, `unauthorized`, `pairing_required`, `concurrent_attempt`, `unpaired`. Also `sendGoodbye()` returns early if `!handshakeComplete` (`SendSpinProtocolHandler.kt:244-247`) |
| M5 | Forward compatibility | Spec (#108) requires ignoring unrecognized payload fields. We do this by construction (explicit key reads), but we also *send* non-spec fields - `client/sync_offset` is an MA extension, and the proxy `auth` message is non-spec |
| M6 | `server_transmitted` ignored | Added to all server->client stream messages (#106); we never read it. It is the reference point for `required_lead_time_ms` |

### 3.6 Connection topology (S4)

Server-initiated connections (client advertises `_sendspin._tcp.local.` on 8928
and runs a WebSocket server) remain unimplemented. We only browse
`_sendspin-server._tcp.` (`NsdDiscoveryManager.kt:31`); grep for `8928` and
`registerService` returns nothing. This is a permitted mode, but it forgoes the
spec's multi-server arbitration: connection ranking (`management` > `playback` >
`pairing`), the 30-second provisional timeout, persisted "last-playback server",
and `client/goodbye` reasons `another_server` / `concurrent_attempt`.

`ConnectionCoordinator` is a single-server reconnect driver - its "priority"
ordering is over *transports* (LOCAL/REMOTE/PROXY), not servers
(`ConnectionCoordinator.kt:337-360`).

This matters more after pairing lands: pairing is initiated by the server moving
an established connection into the `pairing` activity, which is far more natural
in the server-initiated model.

### 3.7 Obsoleted by spec churn

Items from the June audit that should **not** be worked on as written:

- **P1.1 "move `state` to top-level payload"** - the field was renamed to
  `available: boolean` (#115). Implement P4 above instead.
- **P3.6 "`external_source` client state"** - that tri-state no longer exists.
  The replacement is the External Source Handling section: stay
  `available: true` for interruptible activity, or send `available: false` for
  non-interruptible. Our `AUDIOFOCUS_LOSS` handler
  (`PlaybackService.kt:2532-2541`) maps to `available: false`;
  `AUDIOFOCUS_LOSS_TRANSIENT` should map to the `available: false` then
  immediately `available: true` "leave the group" idiom, or to nothing.
- **P3.9 "server-initiated connections: build vs library"** - the build-vs-library
  question is settled. `sendspin-jvm` has no encryption layer; it cannot be the
  answer for any of Phase 1.

---

## 4. Strategic decisions

These are the load-bearing choices behind the phasing. Each should be confirmed
before the phase that depends on it starts.

**D1. Build the Noise layer in-repo; do not adopt `sendspin-jvm`.**
It has no crypto surface at v0.3.3. Revisit only if it ships encryption.

**D2. Ship `pairing_psk` only in Phase 1-2; defer both PIN methods.**
The spec requires exactly this of clients. It removes CPace, PSK wrapping, the
commit/reveal nonce exchange, the failure counter, and the pairing window from
the critical path. `client/hello` advertises `supported_pair_methods:
[{method: "pairing_psk", locations: ["operator"]}]` and the user pairs by
copying a token (or scanning a QR) from Settings into MA.

**D3. Crypto primitives from BouncyCastle; write the KKpsk2 state machine by hand.**
`KKpsk2` is a fixed three-message pattern needing only X25519, HKDF-SHA256,
SHA-256, ChaCha20-Poly1305 and AES-GCM - all in `bcprov-jdk18on`, which is already
a common Android dependency and has no NDK component.
The alternative, `org.signal.forks:noise-java` (Signal's maintained fork of
Noise-Java), should be spiked first: **verify it can construct
`Noise_KKpsk2_25519_ChaChaPoly_SHA256` and that it exposes the handshake hash
`h`**, which the spec needs for the re-handshake prologue and (later) the CPace
`sid`. If it does, use it; it is a large amount of well-reviewed code we do not
have to write. If it does not expose `h`, hand-roll.

**D4. Use the stored-pubkey record model, not the shared-PSK model.**
Android has no storage pressure. Binding each PSK to a `server_id` is the
stronger option and is what `management/list-records` reports.

**D5. Keep the legacy dialect behind a flag through Phase 2.**
Users on MA stable (2.9.x) and older builds must keep working while the encrypted
path stabilizes. Detect by outcome: attempt the spec handshake, and on a
handshake-phase close, fall back once to the legacy `client/hello` path and
surface it in the UI as an insecure connection. Remove the fallback when MA drops
`allow_legacy_clients`.

**D6. Verify against the conformance harness, not by inspection.**
`.github/workflows/conformance.yml` already runs `Sendspin/conformance` from
aiosendspin to SendSpinDroid via the `conformance-client` module. Every phase
below should expand the `SUPPORTED` scenario set in that workflow rather than
adding bespoke tests, so spec drift keeps surfacing in CI.

---

## 5. Execution plan

Phases are ordered by dependency. Within a phase, numbered items are independent
enough to parallelize across agents unless a dependency is called out.

Each item is written to be picked up cold: it names the spec section, the files,
and the acceptance check.

### Phase 0 - Spikes (blocking, ~1-2 days)

| # | Task | Acceptance |
|---|---|---|
| 0.1 | Spike `org.signal.forks:noise-java:0.1.1` on Android. Can it build `Noise_KKpsk2_25519_ChaChaPoly_SHA256`? Does it expose the handshake hash `h`? Does it support `25519_AESGCM_SHA256`? APK size delta? | A throwaway test that completes a KKpsk2 handshake against a Python `noiseprotocol` peer, or a written finding that it cannot. Resolves D3 |
| 0.2 | Stand up a local aiosendspin 9.1.0 server with `allow_unencrypted=False` as the development target | A documented `docs/` runbook other agents can follow. This is the reference peer for all of Phase 1 |
| 0.3 | Confirm MA's pairing UX for `pairing_psk`: where does the operator paste a pairing token? | Screenshot or note in the runbook. Drives the Settings UI design in 2.4 |

### Phase 1 - Encrypted transport (S1, the deadline work)

Depends on 0.1, 0.2. This phase ends with an encrypted, *unpaired* connection
working - Sentinel PSK plus unpaired access - which is the smallest thing that
survives `allow_legacy_clients=False`.

| # | Task | Spec ref | Files | Acceptance |
|---|---|---|---|---|
| 1.1 | Identity keypair: generate a Curve25519 keypair on first run, persist the private key in `EncryptedSharedPreferences`, expose the base64url public key (43 chars, no padding) as `client_id`. Migrate off the UUID | `connection.md#identities` | `UserSettings.kt:181-215`, new `sendspin/crypto/` | `client_id` is 43 base64url chars and stable across app restarts. Old UUID discarded with a one-time migration |
| 1.2 | Noise KKpsk2 driver: responder role, both cipher suites, prologue = exact wire bytes of `client/init` followed by `server/init`, expose handshake hash `h` | `connection.md#encryption`, `#prologue` | new `sendspin/crypto/NoiseSession.kt` | Unit tests against vectors captured from aiosendspin. **The prologue must hash raw sent/received bytes, not a re-encoding** - this is the single most likely source of a silent handshake failure |
| 1.3 | Sentinel PSK constant + `psk_id` derivation `base64url(SHA-256("sendspin-psk-id-v1" \|\| PSK))` | `connection.md#pre-shared-key` | `sendspin/crypto/` | Derives the published constant `GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo` from the published Sentinel PSK. Use this as the test vector |
| 1.4 | Wire layer: `client/init` / `server/init` / `noise/handshake` as text frames; after handshake, all messages as binary frames with a leading type byte (`0` = JSON). Rework `BaseWebSocketTransport` so the protocol layer sends binary | `messaging.md#communication` | `BaseWebSocketTransport.kt:172-192`, `SendSpin.kt:317-323`, `SendSpinProtocolHandler.kt` | Round-trips JSON messages through the encrypted channel against aiosendspin |
| 1.5 | Fragmentation (types `2`/`3`), send and receive. One fragmented message in flight per direction; malformed sequences close the connection | `messaging.md#fragmentation` | `BinaryMessageParser.kt`, new fragment reassembler | Unit tests for: open/continue/end, `orig_type` of 2 or 3 rejected, fragment-end with nothing in flight rejected, non-fragment frame mid-reassembly rejected |
| 1.6 | `server/activate`: parse `activities`, `active_roles`, `pairing`. Persist `active_roles` across activations that omit it. Enforce the admissibility table and pick `pairing_required` vs `unauthorized` by the spec's first-match rule | `messaging.md#server--client-serveractivate` | `MessageParser.kt`, `SendSpinProtocolHandler.kt` | The worked example in the spec (Sentinel + `['playback']` vs `['playback','management']`) produces the two different goodbye reasons |
| 1.7 | Gate all client output on the first `server/activate`; stop reading `active_roles`/`connection_reason` from `server/hello` | `messaging.md#server--client-serveractivate` | `SendSpinProtocolHandler.kt:566-590`, `MessageParser.kt:29-49` | No `client/time` or `client/state` on the wire before the first `server/activate` |
| 1.8 | `client/hello`: add `trust_level`, `supported_pair_methods`, `unpaired_access`; remove `client_id` and `version` (they moved to `client/init`) | `messaging.md#client--server-clienthello` | `MessageBuilder.kt:19-79` | Matches the spec field list exactly; aiosendspin accepts it without warnings |
| 1.9 | `client/state`: replace the `state` string with `available: boolean`. Do not report `available: true` until the time filter has converged | `messaging.md#client--server-clientstate` | `MessageBuilder.kt:101-134`, `SendSpinProtocolHandler.kt:319-399` | First `client/state` carries `available: false` (or is withheld) until `timeFilter.isConverged` |
| 1.10 | Legacy fallback behind D5: try spec handshake, fall back once to the legacy dialect on handshake-phase failure, surface an "unencrypted" indicator in the UI | - | `SendSpin.kt`, connection UI | Connects to both MA 2.9.x (legacy) and an `allow_unencrypted=False` server |

**Phase 1 exit criterion:** the conformance harness passes the encrypted
unpaired-playback scenario against aiosendspin with `allow_unencrypted=False`,
and audio plays.

### Phase 2 - Pairing (Pairing PSK) and trust storage (S1)

Depends on Phase 1.

| # | Task | Spec ref | Acceptance |
|---|---|---|---|
| 2.1 | Trust store: persist long-term PSK records (`psk_id`, `psk`, `server_id`, `used`) in encrypted storage. Enforce the single `psk_id` namespace across Sentinel / Pairing PSK / records | `management.md#records`, `connection.md#pre-shared-key` | Records survive reboot; a colliding `psk_id` is rejected |
| 2.2 | Pairing PSK: generate per-device from a CSPRNG at first run, persist, never self-rotate. Keep it in the handshake candidate set whenever the method is enabled | `pairing.md#pairing-psk-flow` | A server re-handshake to the Pairing PSK succeeds without any pairing activity running |
| 2.3 | PSK selection: decrypt the Noise message-1 payload, match `psk_id` against candidates, apply the stored-pubkey `server_id` check on match, fail the handshake on a miss | `connection.md#pre-shared-key` | Wrong `server_id` fails the handshake |
| 2.4 | Pairing token: encode `SP:0` + base32(client_key \|\| pairing_psk) with `2`->`9`. Surface as selectable text and a QR code in Settings | `pairing.md#pairing-token` | Encoder reproduces the spec's reference vector exactly (it is in `pairing.md`). Decoder round-trips and rejects malformed input |
| 2.5 | Pairing flow: on a `pairing` activation with `method: pairing_psk`, verify the matched PSK **is** the Pairing PSK (abort `method_not_supported` otherwise), send `client/pair-finalize` with `long_term_psk`, persist on `server/pair-finalize` | `pairing.md#pairing-psk-flow` | Full pair against aiosendspin; record persists; reconnect authenticates at `trust_level: user` |
| 2.6 | In-band re-handshake: rerun the handshake in transport mode, prologue = prior `h`, no re-sent init messages, then `server/hello` -> `client/hello` (re-asserting `trust_level`) -> `server/activate` | `connection.md#re-handshake` | Pairing completes and the channel promotes without the socket closing |
| 2.7 | `server/unpair`: remove the record, send `client/goodbye` reason `unpaired`, close. Do **not** remove a shared-PSK record. Ignore at `trust_level: none` | `messaging.md#server--client-serverunpair` | All three branches covered by tests |
| 2.8 | Pairing UI: pairing state, token display, paired-servers list with a local "forget" action | - | A user can pair from a clean install without developer tooling |
| 2.9 | `pair/abort` send and receive, with the reason enum | `pairing.md#client--server-pairabort` | `attempt_timeout` fires at the recommended 2 minutes |

**Phase 2 exit criterion:** pair from a clean install against MA with
`allow_legacy_clients=False`, reconnect at `trust_level: user`, play audio.

### Phase 3 - Management, correctness, and player quality (S2)

Independent of Phase 2 except where noted. 3.4-3.10 can start immediately, in
parallel with Phases 1-2, since they touch the audio path rather than the
handshake.

| # | Task | Spec ref | Notes |
|---|---|---|---|
| 3.1 | `management/*` request handling and `management/result` replies, gated on `'management'` in activities and a long-term PSK match (`permission_denied` otherwise). At most one in flight | `management.md` | Depends on 2.1 |
| 3.2 | `management/get-pairing-config` / `set-pairing-config` as a patch, with `record_mode` and `unpaired_access` | `management.md#pairing-config` | Depends on 3.1 |
| 3.3 | `management/list-records`, `add-record`, `remove-record`, incl. removing the requester's own record then closing with `unauthorized` | `management.md#records` | Depends on 3.1. Storage accounting may be omitted (spec permits omitting `storage` when the pool is effectively unbounded) |
| 3.4 | **Sync accuracy (P1-P3):** bring `MAX_SPEED_CORRECTION` to 0.005, tighten `DEADBAND_THRESHOLD_US` toward 500-1000 us, add a one-shot snap when error would exceed 1 ms. Size the correction step per the spec's `N = round(21us * sample_rate / 1e6)` | `roles/player/v1.md#playback-synchronization` | The highest-value non-deadline item. Tune the three constants together and measure; do not change them independently |
| 3.5 | Volume: apply `(volume/100)^1.5`, add a short ramp, make mute a separate gain stage so volume changes cannot unmute, persist `muted` | `roles/player/v1.md` (P6-P9) | Requires reworking mute off "set stream volume to 0" |
| 3.6 | Drop audio chunks whose timestamp is already in the past during playback, not only at start gating | `roles/player/v1.md#sync-accuracy` (P10) | |
| 3.7 | Honor the `roles` array on `stream/clear` | `messaging.md#server--client-streamclear` (P11) | One-line dispatch fix plus parsing; currently an artwork clear wipes the audio pipeline |
| 3.8 | Persist auto-measured `static_delay_ms` per output device across reboots | `roles/player/v1.md` (P12) | |
| 3.9 | `server/state` shallow-merge and explicit-null clearing for `metadata`, `controller`, `color`; distinguish `JsonNull` from absent | `messaging.md#server--client-serverstate` (M1, M2) | Fixes delta updates synthesizing blank metadata |
| 3.10 | `client/goodbye`: send `another_server`, `shutdown`, `unauthorized`, `pairing_required`, `concurrent_attempt`, `unpaired` at the right moments; allow sending pre-handshake | `messaging.md#client--server-clientgoodbye` (M4) | `another_server` is needed today, independent of everything else |
| 3.11 | Controller role: implement `seek` / `seek_relative` with `position_ms` / `offset_ms`, parse `seek_max_ms`, wire the dormant commands to real callers, and consume `controllerState` to gate the UI | `roles/controller/v1.md` | We claim this role, so the spec requires all of it. Currently `seekTo`, `setRepeatMode`, `setShuffleModeEnabled` are no-ops |
| 3.12 | Metadata: surface `album_artist`, `year`, `track` through the callback and `PlaybackState` | `roles/metadata/v1.md` | Parsed already, dropped at the callback boundary |
| 3.13 | External source handling: map `AUDIOFOCUS_LOSS` to `available: false`, and decide the `LOSS_TRANSIENT` policy | `messaging.md#external-source-handling` | Replaces the removed `external_source` state |

### Phase 4 - Optional roles and modes (S4)

Ordered by value. None are required for compliance.

| # | Task | Notes |
|---|---|---|
| 4.1 | Artwork role properly: multiple channels, `png`/`bmp`, parse the artwork `stream/start` object, route the channel index through to the UI, use the timestamp for scheduled display, send `stream/request-format` to toggle channels via `source: 'none'` | Fixes S2-level gaps in a role we already claim. Note P11 (3.7) is a prerequisite for channel-specific clears to be safe |
| 4.2 | `color@v1` | Cheap and high-visual-payoff: we already theme from artwork locally, so the plumbing is a swap of the color source. Depends on 3.9 for null-clearing |
| 4.3 | `visualizer@v1` with typed frames 16-20 and a UI | Needs `visualizer@v1_support` (types, `buffer_capacity`, `rate_max`, spectrum config) and per-type binary decoding. Types 21-23 are reserved and must not be used |
| 4.4 | PIN pairing methods (`dynamic_pin`, `static_pin`) | Requires CPace-X25519-SHA512 (no JVM library exists - hand-roll from draft-irtf-cfrg-cpace-21), PSK wrapping, the commit/reveal nonce round, a persisted failure counter escalating at 10, and a pairing window with an operator gesture. `dynamic_pin` is the better fit for a phone (display out-channel). Large; only worth it if operators find token pairing awkward |
| 4.5 | Server-initiated connections: advertise `_sendspin._tcp.local.` on 8928, run an embedded WebSocket server, implement multi-server admission (ranking, 30 s provisional timeout, last-playback server persistence) | Largest item here. Becomes more attractive after pairing lands, since the spec's pairing flow assumes the server drives activation |
| 4.6 | `source@v1` | Requires `RECORD_AUDIO`, an outbound binary path, and `client_stream/start`/`end`. Forbidden at `trust_level: none`, so it depends on Phase 2. Speculative for a phone client |

---

## 6. Risks

- **The deadline is unknown.** MA has not announced when `allow_legacy_clients`
  flips or is removed. Phase 1 should be treated as time-boxed work starting now,
  not scheduled against a date we control. Watching
  `music-assistant/server` for changes to `CONF_ALLOW_LEGACY_CLIENTS` is worth a
  standing check.
- **The prologue is the likeliest silent failure (1.2).** It must hash the exact
  wire bytes of `client/init` and `server/init`, not a re-serialization. Kotlin's
  `Json.encodeToString` will not reproduce byte-identical output after a parse
  round-trip. Keep the raw strings.
- **Re-handshake (2.6) has no fallback path.** If it fails mid-session the socket
  is unusable and the spec's failure handling is "close without an application
  error message", which is hard to diagnose. Instrument it heavily.
- **P1/P2 tightening (3.4) risks audible artifacts.** Moving from a 10 ms dead
  band to sub-millisecond will increase correction frequency substantially on
  devices with jittery `AudioTrack.getTimestamp()`. The existing interpolated
  crossfade correction (which is *not* the spec's bit-exact drop/duplicate) may
  help or hurt; measure on real hardware, including the ZTE/Nubia devices the
  repo already has tooling for.
- **`sendspin-jvm` may ship encryption mid-plan.** If it does, re-evaluate D1
  before Phase 2 rather than after.

---

## 7. What to read first

For an agent picking up Phase 1 cold, in order:

1. `connection.md` - the whole file, especially `#encryption`, `#pre-shared-key`,
   `#prologue`, `#re-handshake`
2. `messaging.md` lines 1-30 (handshake order and frame types) and
   `#server--client-serveractivate`
3. `pairing.md#pairing-psk-flow` and `#pairing-token` (skip the PIN flows)
4. This repo: `SendSpinProtocolHandler.kt`, `MessageBuilder.kt`,
   `BaseWebSocketTransport.kt`
5. `aiosendspin/noise/` at tag 9.1.0 - the working reference for every one of
   these, in a language that is easy to read against the spec

---

## 8. Issue index

Every work item in section 5 has a GitHub issue carrying its full implementation
plan (spec quotes, current state with `file:line`, ordered steps, a test-first
test plan, acceptance criteria, risks). Issues are labelled `spec-compliance`
plus `phase-0` .. `phase-4`.

| Item | Issue | Item | Issue | Item | Issue |
|---|---|---|---|---|---|
| 0.1 | #189 | 2.1 | #202 | 3.7 | #210 |
| 0.2 | #190 | 2.2 | #203 | 3.8 | #211 |
| 0.3 | #191 | 2.3 | #204 | 3.9 | #212 |
| 1.1 | #192 | 2.4 | #205 | 3.10 | #213 |
| 1.2 | #193 | 2.5 | #206 | 3.11 | #214 |
| 1.3 | #194 | 2.6 | #223 | 3.12 | #215 |
| 1.4 | #195 | 2.7 | #224 | 3.13 | #216 |
| 1.5 | #196 | 2.8 | #225 | 4.1 | #217 |
| 1.6 | #197 | 2.9 | #226 | 4.2 | #218 |
| 1.7 | #198 | 3.1 | #227 | 4.3 | #219 |
| 1.8 | #199 | 3.2 | #228 | 4.4 | #220 |
| 1.9 | #200 | 3.3 | #229 | 4.5 | #221 |
| 1.10 | #201 | 3.4 | #207 | 4.6 | #222 |
|  |  | 3.5 | #208 |  |  |
|  |  | 3.6 | #209 |  |  |

### Findings from planning that amend section 4

Two decisions changed while the plans were being written. The issues carry the
corrected guidance; this records why.

**D3 (Noise library) now expects a negative result.** Reading
`org.signal.forks:noise-java` 0.1.1 at source shows `Pattern.lookup` has no `PSK`
token and does not recognize `KKpsk2`, and `setPreSharedKey` throws once the
handshake has started - so the PSK cannot be supplied after Noise message 1, which
is exactly what `psk_id` selection requires. No other JVM Noise artifact on Maven
Central implements `psk` modifiers. Hand-rolling KKpsk2 on BouncyCastle's
low-level `org.bouncycastle.crypto.*` API is therefore the likely route. #189
keeps the empirical check but timeboxes it and requires the fallback prototype as
its real deliverable, so #193 starts from working code.

**D2 (`locations` hint) should probably be `["device"]`, not `["operator"]`.**
Music Assistant renders `operator` as "The pairing token is a custom one set on the
device by its operator", which describes a manually-provisioned secret. We generate
the Pairing PSK from a CSPRNG on first run and show it on screen, which
`pairing_psk_location_device` ("printed on the device") describes better. #191
confirms this against a running instance and amends the decision.

**Music Assistant has no QR scanner on the pairing screen.** Its token field is a
single-line text input, and `decode_token` trims only *surrounding* whitespace, so
any interior grouping separator is rejected by `b32decode` with an opaque failure.
The token must therefore be rendered and copied ungrouped. #205 and #225 both
carry this constraint.
