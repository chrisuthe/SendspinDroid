# ConnectionCoordinator Design

**Date:** 2026-05-05
**Status:** Draft, pending review
**Scope:** Centralize connection lifecycle management for SendSpin streaming and Music Assistant control across SendSpinDroid.

---

## 1. Problem

Connection management today is spread across at least seven independent components, each holding partial state and reacting to network events on its own:

- `SendSpinClient` (transport, internal reconnect loop, backoff 500ms-30s, max 20 attempts)
- `MusicAssistantManager` (independent transport, token storage, no network observation)
- `PlaybackService` (Service-scoped network callback, mirrors connection state, owns `currentServerId`)
- `MainActivity` (Activity-scoped network callback, owns local `connectionState`, drives `AutoReconnectManager`)
- `AutoReconnectManager` (outer-loop reconnect, backoff 500ms-60s, separate from SendSpinClient's loop)
- `AddServerWizardActivity` (its own OkHttp client and connection-test paths separate from production)
- `UnifiedServerRepository` (persisted server records, soft "last connected" hint)

Three concrete failure modes are user-visible:

1. **WiFi -> Cell handover prompts re-login.** Any `MaApiTransport.AuthenticationException` thrown during transport handshake (including transport-level handshake failures during a network change) clears the stored MA token and forces login. The classification of "transport failed during handshake" vs. "server actually rejected the token" is collapsed into one exception path.

2. **Errors when selecting library items after re-login.** After re-login the `apiTransport` reference is fresh, but `MaCommandClient` may still hold buffered or in-flight requests against the previous transport. `MaDataChannelTransport` in particular keeps the `apiTransport` field non-null after the channel closes, so writes silently fail. The UI surfaces the resulting RPC errors with no actionable explanation.

3. **Two reconnect loops fight.** `SendSpinClient.attemptReconnect` and `AutoReconnectManager.runReconnectLoop` can both be active at once. The first races with `connectToServer()` calls from the second; the second races with the first's `attemptReconnect` after a transient drop. Behavior depends on which loop happens to schedule sooner.

The "Am I connected?" question has at least five independently-maintained answers (SendSpinClient state flow, PlaybackService mirror, MainActivity cache, MusicAssistantManager state flow, UnifiedServerRepository hint). When they diverge, the UI lies to the user.

A complete map of every initiation path, teardown path, network observer, and state holder is in the four-agent investigation that preceded this design (conversation transcript, 2026-05-05).

## 2. Goals

- **Single source of truth** for "what server is active, and which of its transports are up." Eliminate the five-mirror situation.
- **One network observer.** Replace the duplicate `ConnectivityManager.NetworkCallback` registrations in `PlaybackService` and `MainActivity` with a single Service-scoped one.
- **One retry schedule per transport.** Eliminate the dueling-timer problem.
- **Token policy that distinguishes transport failure from rejection.** Stored Music Assistant tokens are cleared only on a confirmed protocol-level rejection from a fully-handshaked server, never on transport-level handshake failures.
- **Independent streaming and control.** SendSpin streaming continues uninterrupted while Music Assistant control reconnects in the background.
- **Same transport implementation in production and wizard tests.** A bug fix to auth handling reaches both paths in one change.

## 3. Non-goals

- No new connection methods (LOCAL, PROXY, REMOTE remain the three).
- No new audio pipeline. The existing `AudioTrack`-based `SyncAudioPlayer` is unchanged.
- No new wire protocol. SendSpin binary frames, MA RPC, time sync messages all keep their current formats.
- No backwards-compatibility shim with old persisted state. `UnifiedServer` records remain compatible; we are not migrating user data.
- No multi-server-active. Exactly one `UnifiedServer` is the active server at any time, as today.

## 4. Architecture overview

Three layers replace the current tangle.

### Layer 1: `ConnectionCoordinator`

A single instance, owned by `PlaybackService` for its lifetime. Holds:

- The active `UnifiedServer` (one pointer, replaces today's five).
- The single `ConnectivityManager.NetworkCallback`.
- The retry schedule per transport.
- The published `SessionState` (one `StateFlow<SessionState>` consumed by all UI).

Exposes a small surface to callers:

```kotlin
class ConnectionCoordinator {
    val sessionState: StateFlow<SessionState>

    fun setActiveServer(server: UnifiedServer)
    fun disconnect()
    fun retryNow()                       // user-initiated retry of an exhausted transport

    // For wizard test path - factory for transient instances:
    fun newTransientSendSpin(): SendSpin
    fun newTransientMusicAssistant(): MusicAssistant
}
```

### Layer 2: Two concrete transport classes

```kotlin
class SendSpin {
    fun connect(endpoint: SendSpinEndpoint)
    fun disconnect()
    val state: StateFlow<TransportState>
    // Plus protocol-specific surface: time-sync events, audio frame stream,
    // visualizer payload events, etc. Same protocol logic as today's SendSpinClient.
}

class MusicAssistant {
    fun connect(endpoint: MaEndpoint, token: String?)
    fun disconnect()
    val state: StateFlow<TransportState>
    // Plus RPC surface: MaCommandClient, library queries, queue control.
}
```

No interface layer. Two services, fixed forever, no polymorphism gained from hiding them. Test doubles introduced as single-use seams when actually needed.

### Layer 3: Internal transport implementations

Per-method WebSocket and DataChannel implementations are private to `SendSpin` and `MusicAssistant`. Existing `WebSocketTransport` and `WebRtcTransport` machinery is reused.

For REMOTE mode, both classes share an underlying `RemotePeer` resource (the WebRTC peer connection) through an internal package-private holder. Callers of the public API never see this.

### Endpoints

```kotlin
sealed class SendSpinEndpoint {
    data class Local(val address: String, val path: String) : SendSpinEndpoint()
    data class Proxy(val url: String, val authToken: String) : SendSpinEndpoint()
    data class Remote(val remoteId: String) : SendSpinEndpoint()
}

sealed class MaEndpoint {
    data class Local(val address: String, val port: Int) : MaEndpoint()
    data class Proxy(val baseUrl: String) : MaEndpoint()
    data class Remote(val remoteId: String) : MaEndpoint()
}
```

These replace the three explicit `connectLocal/connectProxy/connectRemote` methods on today's `SendSpinClient` with a single `connect(endpoint)`.

### What goes away

- `AutoReconnectManager` deletes entirely.
- `SendSpinClient`'s reconnect loop and backoff logic delete (Coordinator owns retry).
- `MainActivity.networkCallback` deletes (Coordinator owns observation).
- `MusicAssistantManager`'s `handleConnectionFailure(clearTokenForServer = ...)` logic collapses to a single Coordinator-level token-clear call.
- `SendSpinClientCallback` interface deletes; replaced by `StateFlow` observation.
- `PlaybackService.currentServerId` and `currentConnectionMode` mirrors delete.

## 5. State machines

### Per-transport state

Both `SendSpin` and `MusicAssistant` expose the same four-state machine:

```
Idle --connect()--> Connecting --handshake ok--> Ready
  ^                     |                          |
  |                     v                          v
  |                  Failed(reason) <--- transport drops mid-session
  |                     |
  |                     v
  |              (Coordinator decides:
  |                 retry -> Connecting
  |                 fallback to next endpoint -> Connecting
  |                 give up -> stay Failed
  |                 user disconnect -> Idle)
  |
  +---- disconnect() (from any state, always reaches Idle)
```

The transport reports state changes; it never decides retry. That decision lives in the Coordinator.

### `FailureReason` classification

```kotlin
sealed class FailureReason {
    object TransientNetwork : FailureReason()    // timeout, socket close, DNS, refused
    object HandshakeFailed : FailureReason()     // TLS error, version mismatch, protocol error pre-handshake
    object AuthRejected : FailureReason()        // server completed handshake then returned 401/equiv
    object ProtocolError : FailureReason()       // server sent garbage we can't parse
    object Exhausted : FailureReason()           // Coordinator gave up after N attempts
}
```

| Reason | Coordinator response | Token effect (MA only) |
|---|---|---|
| `TransientNetwork` | retry with backoff | preserve |
| `HandshakeFailed` | retry with backoff | preserve |
| `AuthRejected` | surface "please log in again", set Idle | clear |
| `ProtocolError` | retry, log warning | preserve |
| `Exhausted` | stay Failed until manual retry or network change | preserve |

`AuthRejected` requires a successful transport handshake before classification. Every WiFi->Cell handover failure short of "MA server actually rejects this token" hits `TransientNetwork` or `HandshakeFailed`, never `AuthRejected`. Token survives indefinitely under transient conditions.

### `SessionState` (Coordinator-level, what UI sees)

```kotlin
data class SessionState(
    val server: UnifiedServer?,           // null = no server selected
    val sendSpin: TransportState,
    val musicAssistant: TransportState
)
```

UI derives whatever it needs from these three fields. No mega-rolled-up enum. The matrix of "audio up + control down" combinations is too combinatorial to enumerate; a flat record exposes exactly what's true.

UI rules:

- Show "now playing" UI iff `sendSpin is Ready`. Audio buffer drains under us, so this stays `Ready` even mid-network-blip thanks to the existing `SyncAudioPlayer` buffer.
- Show MA library iff `musicAssistant is Ready`; show grayed-out "reconnecting" overlay otherwise.
- Show error dialog iff either transport is `Failed(Exhausted)` or `Failed(AuthRejected)`.
- Show "Reconnecting..." badge whenever a transport is `Connecting` and was previously `Ready`.

## 6. Coordinator policies

### Retry schedules

Per-transport, with different costs in mind.

| | SendSpin | MusicAssistant |
|---|---|---|
| Backoff | 1s, 2s, 4s, 8s, 16s, 30s cap | 0.5s, 1s, 2s, 4s, 8s cap |
| Max attempts before `Exhausted` | 15 | 30 |
| Reasoning | thrashing the audio buffer is audible | control reconnects are cheap |

Counters reset on:

- Transport reaches `Ready`.
- Network identity changes (new `Network` handle from `ConnectivityManager`).
- User explicitly retries via UI.

Counters are independent per transport. MA exhausting does not affect SendSpin's counter, and vice versa.

### Endpoint fallback

Replaces today's `LOCAL_RECONNECT_FALLBACK_THRESHOLD = 3` hardcode in `SendSpinClient.attemptReconnect`.

After three consecutive `TransientNetwork` or `HandshakeFailed` failures against an endpoint, the Coordinator switches to the next available endpoint from the active `UnifiedServer` config. Order depends on current network classification:

- On LAN (mDNS-validated or saved subnet match): `Local -> Proxy -> Remote`.
- Off LAN (cellular, foreign WiFi): `Remote -> Proxy`. `Local` is skipped because the LAN address is physically unreachable.
- All endpoints exhausted: transport goes `Failed(Exhausted)`. Stays there until network change or user retry.

This is the new home for the logic in today's `ConnectionSelector`, except it is invoked on every fallback decision rather than once at connect time.

### Network handover

Replaces today's aggressive `disconnectForReselection()` cascade in `PlaybackService.networkCallback.onLinkPropertiesChanged`.

When the Coordinator sees a network identity change (different `Network` handle, capability change WiFi <-> Cellular):

1. **Do not preemptively tear down transports.** TCP can survive some changes (e.g. WiFi roaming on the same LAN) and tearing down on every change interrupts streaming unnecessarily.
2. Reset SendSpin time-sync baseline (soft refresh; existing `onNetworkChanged` behavior).
3. Re-evaluate endpoint eligibility. A `Local` endpoint becomes ineligible when the device leaves the LAN.
4. If a transport's current endpoint is now ineligible, the Coordinator transitions it to `Failed(TransientNetwork)` itself, which triggers normal fallback flow.
5. If a transport keeps working despite the change, it stays `Ready`. No interruption.
6. Reset retry counters on every transport — fresh network, fresh budget.

The WiFi -> Cell flow that breaks today's user experience now goes:

- WiFi disappears: SendSpin and MA both fail with `TransientNetwork` (sockets dead). Tokens preserved because no `AuthRejected` occurred.
- Cellular comes up: Coordinator sees `Local` endpoint ineligible, falls back to `Proxy` or `Remote`.
- Both transports re-handshake against the new endpoint.
- MA `connect()` sends the still-stored token.
- If MA server accepts: `Ready`, no login prompt. (Expected case.)
- If MA server rejects with 401: `AuthRejected`, token cleared, login required. (Correct: token is actually bad.)
- If anything else fails: `TransientNetwork` or `HandshakeFailed`, token preserved, retry continues.

### Token policy enforcement

The MA token, stored per-server-id in `MaSettings`, is cleared at exactly one site: when the Coordinator observes `musicAssistant.state == Failed(AuthRejected)`. By construction this requires a completed handshake.

Today's multiple paths that transitively call `clearTokenForServer` (in `MusicAssistantManager.handleConnectionFailure`) all collapse to this one site.

### Stall watchdog

The existing stall watchdog (no-data-for-N-seconds -> force close -> reconnect) stays inside `SendSpin` as a private detail. When it trips, it internally transitions to `Failed(TransientNetwork)`. Coordinator handles the rest. No special "reselection" path needed.

### User-initiated disconnect

`coordinator.disconnect()` sets `currentServer = null`, calls `disconnect()` on both transports, both transition to `Idle`. No retry attempts queued. The single source of truth for "the user explicitly stopped."

### Boot and auto-start

`BootReceiver` -> `PlaybackService.onStartCommand(ACTION_AUTO_CONNECT)` -> `coordinator.setActiveServer(defaultServer)`. Same code path as user picking a server from the list. The "tap-to-resume notification" path on Android 15+ funnels through the same entry point.

## 7. Wizard test path

The `AddServerWizardActivity` connection tests today create an independent OkHttp client and run their own handshake logic, separate from production. This is the source of leak risk on wizard cancel and a duplicate code path that has to be kept in sync with production auth handling.

In the new design, the wizard creates a *transient* `SendSpin` or `MusicAssistant` instance (via `coordinator.newTransientSendSpin()` / `newTransientMusicAssistant()`), calls `connect(endpoint)`, observes `state` until it reaches `Ready` or `Failed`, then calls `disconnect()` and discards the instance.

Properties of the transient path:

- Same transport implementation as production. Auth fixes reach both in one change.
- No retry. Transient instances are not orchestrated by the Coordinator's retry policy; the wizard wants fail-fast.
- Activity owns the lifecycle. Wizard `onDestroy` calls `disconnect()` on any active transient instance to prevent socket leaks on cancel.
- No interaction with the active session. Transient instances do not observe network events through the Coordinator and do not affect `sessionState`.

Today's standalone OkHttp client in `AddServerWizardActivity:365` and the duplicated handshake logic delete.

## 8. UI consumer model

All UI consumers switch to a single subscription on `coordinator.sessionState`:

- `MainActivity` observes the flow. Replaces the broadcast receiver pattern at `MainActivity:1881` and the local `connectionState` cache at `MainActivity:163`.
- `MediaSession` custom commands (today routed through `PlaybackService.onCustomAction:2730`) now call `coordinator.setActiveServer(...)` / `coordinator.disconnect()`.
- The foreground notification reads `coordinator.sessionState` for title/subtitle.
- Android Auto browse tree degrades gracefully when `musicAssistant` is not `Ready` (browse tree shows a "Reconnecting..." root entry instead of failing).

`MainActivity` no longer registers its own `ConnectivityManager.NetworkCallback`. Snackbar messages about lost network are derived from `sessionState` transitions.

## 9. Migration phasing

Seven phases. Each phase is independently shippable. Each phase leaves the app working. Beta testers can be on any phase without confusion.

| Phase | Change | Goal |
|---|---|---|
| 1 | Introduce `ConnectionCoordinator` as an adapter wrapping today's `SendSpinClient` and `MusicAssistantManager`. Callers (`PlaybackService`, `MainActivity`) start going through it. No behavior change. | Validate the API surface |
| 2 | Delete `AutoReconnectManager`. Move its loop into Coordinator. `SendSpinClient`'s `attemptReconnect` becomes a Coordinator-driven retry. | Kill dueling-timer problem |
| 3 | Coordinator takes over the single `ConnectivityManager.NetworkCallback`. Delete duplicates in `PlaybackService` and `MainActivity`. | Single observer |
| 4 | Rename and simplify `SendSpinClient` -> `SendSpin`. Sealed `SendSpinEndpoint`, `StateFlow<TransportState>`, no more `SendSpinClientCallback`. | Clean transport API |
| 5 | Rename and simplify `MusicAssistantManager` -> `MusicAssistant`. `FailureReason` classification surfaces. Token-clearing collapses to one site. | **WiFi->Cell login bug fully fixed** |
| 6 | Wizard test path switches to transient `SendSpin` and `MusicAssistant` instances. Delete wizard's standalone OkHttp client. | Single transport code path |
| 7 | Five-sources-of-truth cleanup: `PlaybackService` stops mirroring connection state; `MainActivity` stops caching it; `UnifiedServerRepository.lastConnectedMs` becomes the only persisted hint. | One source of truth |

User-visible improvements appear progressively:

- After Phase 2: reconnect timing becomes predictable (no more dueling timers).
- After Phase 3: network change handling consistent regardless of whether app is foreground.
- After Phase 5: WiFi->Cell handover stops asking for re-login. "Errors when selecting items after re-login" stops happening because token-clearing is no longer triggered by transient failures.
- After Phase 7: removal of state divergence bugs that surface as "UI says connected, but command fails."

## 10. Testing strategy

The brainstorm did not deeply discuss testing. Initial outline:

- **Unit tests for Coordinator policy.** Inject fake `SendSpin` and `MusicAssistant` (single-use seam at the field level — Kotlin allows constructor injection without a public interface). Verify retry schedules, fallback ordering, and the token-clearing rule (only on `AuthRejected`).
- **Unit tests for transport state classification.** Each transport's exception-to-`FailureReason` mapping is the most regression-prone surface. Cover at least: timeout, socket close, TLS error, malformed protocol, server-side 401, server-side close with auth-related code.
- **Integration test for WiFi->Cell.** Simulate network identity change while both transports are `Ready`. Assert: token preserved, `Local` endpoint marked ineligible, fallback fires, `sessionState` reaches `Ready` again.
- **Existing audio-pipeline tests** stay valid; they test below the Transport seam.

## 11. Open questions

- **Exhaustion budgets** (15 SendSpin / 30 MA) are guesses based on today's `MAX_TOTAL_RECONNECT_ATTEMPTS = 20`. They may want tuning after beta testing. Not user-configurable in the initial design.
- **Captive portal handling** is not addressed. Today's code shows a generic snackbar; we inherit that behavior. If captive portal becomes a real-world pain point a future phase can add explicit detection and a portal-launch UI.
- **Multiple simultaneous wizard tests** (e.g. user runs SendSpin test then immediately tries MA test) are not specifically modeled; each gets its own transient instance, so they should be independent, but this needs a test.
- **Process death recovery.** If `PlaybackService` is killed and recreated, the Coordinator restarts cold. The persisted `lastConnectedMs` hint in `UnifiedServerRepository` provides "intent" but not "in-flight state." Acceptable for v1; revisit if it produces user-visible problems.

## 12. References

- Conversation transcript 2026-05-05: four-agent investigation mapping initiation paths, disconnection paths, network change reactors, and state ownership.
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` (today's SendSpin client)
- `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt` (today's MA manager)
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` (today's service)
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (today's UI host)
- `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt` (today's wizard)
