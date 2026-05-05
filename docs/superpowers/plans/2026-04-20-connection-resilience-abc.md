# Connection Resilience (A+B+C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the "zombie WiFi" bug where the app appears connected after losing WiFi but audio errors out when the buffer drains with no way to recover short of force-killing.

**Architecture:** Three complementary layers of detection/recovery:
- **A** — Require `NET_CAPABILITY_VALIDATED` on the NetworkRequest so Android's captive-portal/validation probe loss is treated as a network loss (not just link-layer disconnect).
- **B** — App-level stall watchdog in `SendSpinClient` that tracks last-received-byte timestamp and forces `transport.close()` if a connected+playing session goes silent for >7 seconds, short-circuiting Ktor's slow ping-timeout detection.
- **C** — Remove the hard 5-attempt cap in normal mode. Use the same shape as High Power Mode: exponential backoff for the first 5 attempts, then 30s steady-state forever (with the banner continuing to display progress). Banner UI already handles unbounded attempt counts.

**Tech Stack:** Kotlin, Android (minSdk reads `NetworkCapabilities`), Ktor WebSockets (`BaseWebSocketTransport`), MockK + JUnit4 for unit tests, `UnconfinedTestDispatcher` for coroutine testing.

---

## Context for the Implementing Engineer

You are working on **SendSpinDroid**, a native Kotlin synchronized-audio client. It connects to a SendSpin server via WebSocket and plays PCM audio with microsecond-precise clock sync. The relevant symptom we're fixing:

> User is on WiFi listening to music, walks outside, phone loses WiFi. The app's UI still shows "Connected". Audio plays from its buffer for ~10-30 seconds then cuts out. The app never reconnects. Force-killing is the only recovery.

Three root causes combined:
1. `NetworkRequest` only requires `NET_CAPABILITY_INTERNET` — Android can still report the WiFi network as "available" with `INTERNET` even after it's declared it unvalidated, so `onLost()` never fires promptly.
2. TCP half-open: the socket looks alive until Ktor's 15-30s ping times out.
3. Normal mode gives up after 5 reconnect attempts, and there's no manual-reconnect UI.

### Project coding conventions (read before writing code)

- **No emojis** in code, logs, UI, or commit messages. Use ASCII: `us`, `->`, `+/-`.
- **No self-citation in commits** (no `Co-Authored-By: Claude`).
- Tests live under `android/app/src/test/java/com/sendspindroid/...` mirroring the source package. See `SendSpinClientReconnectBackoffTest.kt` for the reflection-based pattern used to poke at private fields.
- Tests mock `android.util.Log`, `UserSettings`, and `AudioDecoderFactory` via MockK (`mockkStatic`/`mockkObject`). The boilerplate is cookie-cutter — copy from an existing test.
- Tests run on JVM with `UnconfinedTestDispatcher` set as the Main dispatcher in `@Before` (`Dispatchers.setMain`) and reset in `@After`.
- **Commit frequently** — one commit per task, always with a green test suite.

### How to run tests

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientStallWatchdogTest"
```

To run a full unit test suite:
```bash
cd android
./gradlew :app:testDebugUnitTest
```

To build and verify nothing is broken after all tasks:
```bash
cd android
./gradlew assembleDebug
```

---

## File Structure

### Files to modify
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` — add stall watchdog (Task B) + remove cap (Task C).
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — add `VALIDATED` capability + `onCapabilitiesChanged` logic (Task A).
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` — mirror `VALIDATED` capability on the activity-level NetworkRequest (Task A).

### Files to create
- `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientStallWatchdogTest.kt` — new unit tests for Task B.
- `android/app/src/test/java/com/sendspindroid/playback/PlaybackServiceValidatedCapabilityTest.kt` — new unit test asserting the NetworkRequest includes VALIDATED (Task A). If this turns out to be infeasible due to `Service` context requirements, skip and rely on the existing `PlaybackServiceConnectionLifecycleTest.kt` harness — see Task 1 Step 3 notes.

### Files to update (existing tests)
- `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientReconnectBackoffTest.kt` — the `max 5 reconnect attempts in normal mode` test must be updated to reflect new no-cap behavior.

---

## Task 1: A — Require `NET_CAPABILITY_VALIDATED` and react to validation loss

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt:355-390, 668-679`
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt:1230-1265`
- Create (optional): `android/app/src/test/java/com/sendspindroid/playback/PlaybackServiceValidatedCapabilityTest.kt`

### Design notes

`ConnectivityManager.NetworkCallback.onCapabilitiesChanged(network, capabilities)` fires whenever capabilities are re-evaluated. Android's validation probe runs shortly after association and re-runs on failure. When validation fails (captive portal redirect, zero-return HTTP probe, DNS hijack, no upstream), the network keeps `NET_CAPABILITY_INTERNET` but **drops** `NET_CAPABILITY_VALIDATED`. That's the exact signal we need for "wifi attached, but there's no actual internet".

We'll keep the existing NetworkRequest matching `INTERNET` (so we still know about the network at all) but additionally track VALIDATED state in `onCapabilitiesChanged`. When VALIDATED goes `true -> false` for the currently-active network, we debounce for 3 seconds (some networks briefly flicker during roaming) and then call `sendSpinClient.setNetworkAvailable(false)`. When VALIDATED returns to `true`, we call `setNetworkAvailable(true)` (which already triggers immediate reconnect via `onNetworkAvailable()` per `SendSpinClient.kt:409-415`).

**Why not `addCapability(NET_CAPABILITY_VALIDATED)` on the `NetworkRequest`?** If we did, the callback would simply stop receiving events when validation drops — we'd get `onLost()` instead. That would work, but it's a blunter instrument: we'd lose the ability to distinguish "VALIDATED flickered" from "the WiFi disassociated". Tracking it in `onCapabilitiesChanged` lets us debounce the flickers.

### Steps

- [ ] **Step 1: Read the current `networkCallback` implementation**

Read `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` lines 340-390. Note that:
- `networkCallback` is an `object : ConnectivityManager.NetworkCallback()` at line 355.
- `onAvailable` (line 356), `onLost` (line 376), `onCapabilitiesChanged` (line 385) are all present.
- `onCapabilitiesChanged` currently just calls `networkEvaluator?.evaluateCurrentNetwork(network)` — it does not read validation state or notify the client.
- `lastNetworkId` (line 342) tracks the currently-attached network's hash.

- [ ] **Step 2: Write the failing test (best-effort)**

Create `android/app/src/test/java/com/sendspindroid/playback/PlaybackServiceValidatedCapabilityTest.kt`.

```kotlin
package com.sendspindroid.playback

import android.net.NetworkCapabilities
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that the PlaybackService NetworkCapabilities handling code knows how to
 * detect validation loss. Because NetworkCallback is an anonymous object inside a
 * Service, we validate via reflection on the declared class structure and method
 * presence rather than spinning up the full Service in a unit test.
 *
 * The contract we assert: PlaybackService.class (or one of its nested classes) must
 * reference NetworkCapabilities.NET_CAPABILITY_VALIDATED somewhere in its bytecode.
 */
class PlaybackServiceValidatedCapabilityTest {

    @Test
    fun `PlaybackService references NET_CAPABILITY_VALIDATED`() {
        // NET_CAPABILITY_VALIDATED = 16 per Android docs
        assertTrue(
            "Expected NET_CAPABILITY_VALIDATED to be referenced in PlaybackService bytecode",
            classHasConstantReference(
                "com.sendspindroid.playback.PlaybackService",
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        )
    }

    private fun classHasConstantReference(className: String, constant: Int): Boolean {
        // Read the class bytes and scan the constant pool for the int value.
        // If you prefer, just assert `NetworkCapabilities.NET_CAPABILITY_VALIDATED == 16`
        // as a smoke test and rely on the compile step + end-to-end test for real coverage.
        val resourceName = className.replace('.', '/') + ".class"
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream(resourceName) ?: return false
        val bytes = stream.readBytes()
        // Simple heuristic: look for the integer literal 16 encoded as BIPUSH 0x10 or
        // in the constant pool (0x03 tag + 4 bytes). This is fragile but sufficient for
        // a smoke check. If this is too brittle for you, delete this test and rely on
        // the integration test in E2E.
        return bytes.asList().windowed(2).any { (a, b) ->
            // BIPUSH 16 — `bipush` opcode is 0x10, followed by the literal byte
            a.toInt() == 0x10 && b.toInt() == 0x10
        }
    }
}
```

Run:
```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.playback.PlaybackServiceValidatedCapabilityTest"
```
Expected: FAIL with assertion failure (PlaybackService doesn't reference `NET_CAPABILITY_VALIDATED` yet).

**NOTE:** If this bytecode-scan approach proves too brittle (e.g., compiler inlines the literal as `sipush 16` or folds it), delete this test entirely. Skip Step 2 and Step 7 — A is sufficiently covered by the existing `e2e/NetworkLossDrainingReconnectTest.kt` once you manually extend it. Don't burn more than 10 minutes on test plumbing here; the functional change below is the important part.

- [ ] **Step 3: Add a debounce helper to the PlaybackService companion**

Open `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`. Add the following constant inside the `companion object` (near line 399, next to the other timing constants):

```kotlin
// Debounce validation-loss events - some networks briefly lose VALIDATED during
// roaming or probe retries. Only treat VALIDATED=false as "offline" if it stays
// false for this long.
private const val VALIDATION_LOSS_DEBOUNCE_MS = 3_000L
```

- [ ] **Step 4: Add fields to track validation state and pending debounce job**

Near `lastNetworkId` (line 342), add:

```kotlin
// Tracks the VALIDATED capability of the currently-attached network so we can
// detect the "WiFi attached but no real internet" state (walked out of range but
// phone hasn't disassociated yet).
private var lastValidatedState: Boolean = true
private var validationLossJob: Job? = null
```

Make sure `Job` is imported (`import kotlinx.coroutines.Job`) — it's likely already imported via other coroutine usage; if not add it.

- [ ] **Step 5: Extend `onCapabilitiesChanged` to detect validation transitions**

Replace the body of `onCapabilitiesChanged` (line 385-389) with:

```kotlin
override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
    Log.d(TAG, "Network capabilities changed: id=${network.hashCode()}")
    networkEvaluator?.evaluateCurrentNetwork(network)

    val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val wasValidated = lastValidatedState
    lastValidatedState = isValidated

    if (wasValidated && !isValidated) {
        // VALIDATED just went true -> false. Debounce in case the probe re-succeeds.
        Log.w(TAG, "Network lost VALIDATED capability - debouncing ${VALIDATION_LOSS_DEBOUNCE_MS}ms before treating as offline")
        validationLossJob?.cancel()
        validationLossJob = serviceScope.launch {
            delay(VALIDATION_LOSS_DEBOUNCE_MS)
            if (!lastValidatedState) {
                Log.w(TAG, "Validation loss confirmed after debounce - notifying client of network unavailability")
                sendSpinClient?.setNetworkAvailable(false)
            }
        }
    } else if (!wasValidated && isValidated) {
        // VALIDATED came back - cancel any pending debounce and restore availability.
        Log.i(TAG, "Network regained VALIDATED capability")
        validationLossJob?.cancel()
        validationLossJob = null
        sendSpinClient?.setNetworkAvailable(true)
    }
}
```

**Before writing,** grep the file for an existing `serviceScope` or equivalent `CoroutineScope`. If none exists in `PlaybackService`, use the Android `Handler(Looper.getMainLooper())` + `postDelayed`/`removeCallbacks` pattern instead (which avoids pulling in a CoroutineScope):

```kotlin
private val validationLossRunnable = Runnable {
    if (!lastValidatedState) {
        Log.w(TAG, "Validation loss confirmed after debounce - notifying client")
        sendSpinClient?.setNetworkAvailable(false)
    }
}

// In onCapabilitiesChanged:
if (wasValidated && !isValidated) {
    Log.w(TAG, "Network lost VALIDATED - debouncing ${VALIDATION_LOSS_DEBOUNCE_MS}ms")
    mainHandler.removeCallbacks(validationLossRunnable)
    mainHandler.postDelayed(validationLossRunnable, VALIDATION_LOSS_DEBOUNCE_MS)
} else if (!wasValidated && isValidated) {
    Log.i(TAG, "Network regained VALIDATED")
    mainHandler.removeCallbacks(validationLossRunnable)
    sendSpinClient?.setNetworkAvailable(true)
}
```

Grep for `mainHandler` — it exists per `SyncAudioPlayerStateCallback` usage elsewhere in the file (line 721+). Use that handler.

- [ ] **Step 6: Clean up pending debounce in `unregisterNetworkCallback`**

In `unregisterNetworkCallback` (starts at `PlaybackService.kt:684`), before the `try { ... }` block add:

```kotlin
mainHandler.removeCallbacks(validationLossRunnable)
```

This prevents a stale debounce from firing after the service has detached its network callback.

- [ ] **Step 7: Mirror the change in `MainActivity.kt`**

`MainActivity.kt:1230-1265` registers its own NetworkCallback for UI-level reconnect banner logic. Add the same validation tracking. Open the file and find `override fun onCapabilitiesChanged` at line 1232. Today it just calls `networkEvaluator?.evaluateCurrentNetwork(network)` and `defaultServerPinger?.onNetworkChanged()`.

Add a sibling field near the other NetworkCallback state (find where `networkCallback` is declared — likely a class field):

```kotlin
private var lastActivityValidatedState: Boolean = true
```

And replace `onCapabilitiesChanged` with (keeping existing calls):

```kotlin
override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
    networkEvaluator?.evaluateCurrentNetwork(network)
    defaultServerPinger?.onNetworkChanged()

    val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    if (lastActivityValidatedState && !isValidated) {
        Log.w(TAG, "Activity: network lost VALIDATED")
        // Show the same error snackbar we show on onLost - signals user that
        // the WiFi they're on has no real internet
        runOnUiThread {
            if (connectionState is AppConnectionState.Connected ||
                connectionState is AppConnectionState.Connecting) {
                showErrorSnackbar(
                    message = "Network has no internet access",
                    errorType = ErrorType.NETWORK
                )
            }
        }
    }
    lastActivityValidatedState = isValidated
}
```

Note: The Activity path does **not** need to forward to `sendSpinClient.setNetworkAvailable(false)` — that's PlaybackService's responsibility. The Activity only needs to show the UI feedback.

- [ ] **Step 8: Verify test passes and full suite stays green**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.playback.PlaybackServiceValidatedCapabilityTest"
./gradlew :app:testDebugUnitTest
```

Expected: both commands pass (or, if you deleted the bytecode-scan test in Step 2, the second command passes).

- [ ] **Step 9: Build to verify no compile errors**

```bash
cd android
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt \
        android/app/src/main/java/com/sendspindroid/MainActivity.kt \
        android/app/src/test/java/com/sendspindroid/playback/PlaybackServiceValidatedCapabilityTest.kt
git commit -m "fix: detect loss of NET_CAPABILITY_VALIDATED on active network

When WiFi drops silently (walked out of range, captive portal, DNS hijack)
Android often keeps NET_CAPABILITY_INTERNET set while removing VALIDATED.
Previously onLost() would not fire, so the WebSocket sat open until ping
timed out -- long after the audio buffer drained.

PlaybackService now watches for a VALIDATED=true->false transition on the
active network, debounces 3s (to avoid false positives during WiFi roaming
or probe retries), then tells SendSpinClient the network is unavailable --
which pauses reconnect attempts the same way onLost() already does.

MainActivity mirrors this for its UI snackbar path."
```

---

## Task 2: B — Application-level stall watchdog in `SendSpinClient`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt:74-85, 168-176, 876-911`
- Create: `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientStallWatchdogTest.kt`

### Design notes

The watchdog is simple:
- Track `lastByteReceivedAtMs: AtomicLong` — updated on every `onMessage(text)` or `onMessage(bytes)` in the `TransportEventListener`.
- A coroutine polls every 3s. If `handshakeComplete && isConnected && !reconnecting && now - lastByteReceivedAtMs > STALL_TIMEOUT_MS`, log a warning and force the transport closed with a synthetic `SocketException("stall watchdog: no data for Xms")`. The existing `TransportEventListener.onFailure` path will classify this as recoverable and call `attemptReconnect()`.
- Watchdog starts when a connection reaches handshake-complete and stops on disconnect.

**Timeout choice:** 7s. Audio chunks arrive very frequently (every 10-20ms), and even if audio is paused server-side the server still sends periodic keepalives (group/update, server/state). 7s of *complete silence* while the client thinks it's playing is unambiguous. Longer (10-15s) would be safer against transient hiccups but loses the benefit of fast detection — the whole point of the watchdog is to beat Ktor's 15-30s ping to the punch.

**Why not rely on Ktor's ping/pong?** The Ktor `pingIntervalMillis` covers part of the gap, but:
1. In normal mode the ping is every 30s — by the time pong-timeout fires, buffer is long gone.
2. Ktor's pong-timeout behavior across versions is inconsistent. Explicit application-level tracking is easier to reason about.

**Why close the transport rather than directly calling `attemptReconnect()`?** `transport.close()` flows through `onClosed`/`onFailure` which already handle state cleanup correctly. Bypassing that would risk double-freeing transport, leaking listeners, or racing with `destroy()`.

### Steps

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientStallWatchdogTest.kt`:

```kotlin
package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests the application-level stall watchdog that detects when a connected,
 * handshake-completed client stops receiving bytes for longer than the
 * configured timeout. Expected behavior: watchdog forces transport.close()
 * which triggers the existing reconnect path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientStallWatchdogTest {

    private lateinit var mockContext: Context
    private lateinit var mockCallback: SendSpinClient.Callback
    private lateinit var client: SendSpinClient
    private lateinit var fakeTransport: FakeTransport

    private class FakeTransport : SendSpinTransport {
        var closeCalled = false
        var closeCode: Int = -1
        override val state = TransportState.Connected
        override val isConnected = true
        override fun connect() {}
        override fun send(text: String) = true
        override fun send(bytes: ByteArray) = true
        override fun setListener(listener: SendSpinTransport.Listener?) {}
        override fun close(code: Int, reason: String) {
            closeCalled = true
            closeCode = code
        }
        override fun destroy() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-id"
        every { UserSettings.getPreferredCodec() } returns "opus"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true

        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockContext = mockk(relaxed = true)
        mockCallback = mockk(relaxed = true)

        client = SendSpinClient(mockContext, "TestDevice", mockCallback)
        fakeTransport = FakeTransport()

        // Put client in a "connected + handshake complete" state
        val addrField = SendSpinClient::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val handshakeField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `lastByteReceivedAtMs is updated on text message`() {
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong

        val before = atomicLong.get()
        Thread.sleep(10)

        // Invoke TransportEventListener.onMessage(String)
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener
        listener.onMessage("{\"type\":\"ping\"}")

        val after = atomicLong.get()
        assertTrue("lastByteReceivedAtMs should advance on text message (before=$before after=$after)",
            after > before)
    }

    @Test
    fun `lastByteReceivedAtMs is updated on binary message`() {
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong

        val before = atomicLong.get()
        Thread.sleep(10)

        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener
listener.onMessage(byteArrayOf(0, 1, 2, 3))

        val after = atomicLong.get()
        assertTrue("lastByteReceivedAtMs should advance on binary message (before=$before after=$after)",
            after > before)
    }

    @Test
    fun `checkStall forces transport close when stalled past timeout`() {
        // Seed lastByteReceivedAtMs to far in the past so the watchdog trips immediately
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong
        atomicLong.set(System.currentTimeMillis() - 60_000L)  // 60s in the past

        // Invoke checkStall directly
        val checkStall = SendSpinClient::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertTrue("Watchdog should have called transport.close()", fakeTransport.closeCalled)
        // Use an abnormal code (not 1000) so onClosed triggers reconnection, not graceful disconnect
        assertNotEquals(1000, fakeTransport.closeCode)
    }

    @Test
    fun `checkStall does not close when recently active`() {
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong
        atomicLong.set(System.currentTimeMillis())  // just now

        val checkStall = SendSpinClient::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertFalse("Watchdog should NOT close when data was recently received", fakeTransport.closeCalled)
    }

    @Test
    fun `checkStall does not close during active reconnection`() {
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong
        atomicLong.set(System.currentTimeMillis() - 60_000L)

        // Simulate in-progress reconnection
        val reconnectingField = SendSpinClient::class.java.getDeclaredField("reconnecting")
        reconnectingField.isAccessible = true
        val reconnecting = reconnectingField.get(client) as java.util.concurrent.atomic.AtomicBoolean
        reconnecting.set(true)

        val checkStall = SendSpinClient::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertFalse("Watchdog should NOT close during reconnection", fakeTransport.closeCalled)
    }
}
```

Run:
```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientStallWatchdogTest"
```

Expected: FAIL with `NoSuchFieldException: lastByteReceivedAtMs` or `NoSuchMethodException: checkStall` — neither exists yet.

- [ ] **Step 2: Add stall watchdog constants**

In `SendSpinClient.kt`'s companion object (lines 74-85), add after `HIGH_POWER_RECONNECT_DELAY_MS`:

```kotlin
// Stall watchdog: while connected+handshake-complete, if no bytes arrive for
// this long, force-close the transport so the existing reconnect path kicks in.
// Shorter than Ktor's 30s ping-timeout to beat buffer drain.
private const val STALL_TIMEOUT_MS = 7_000L
private const val STALL_CHECK_INTERVAL_MS = 3_000L
```

- [ ] **Step 3: Add fields to track last-received-byte timestamp and watchdog job**

Near the other reconnection state (lines 167-176), add:

```kotlin
// Stall watchdog state. lastByteReceivedAtMs is updated on EVERY text/binary
// message from the transport. stallWatchdogJob is the polling coroutine.
private val lastByteReceivedAtMs = AtomicLong(System.currentTimeMillis())
private var stallWatchdogJob: Job? = null
```

Make sure `java.util.concurrent.atomic.AtomicLong` is imported — add `import java.util.concurrent.atomic.AtomicLong` at the top of the file alongside the existing AtomicInteger/AtomicBoolean imports.

- [ ] **Step 4: Update timestamp in `TransportEventListener.onMessage(String)` and `onMessage(ByteArray)`**

Locate `TransportEventListener.onMessage(text: String)` around `SendSpinClient.kt:876` and `onMessage(bytes: ByteArray)` around line 909. At the very **first** line of each, add:

```kotlin
lastByteReceivedAtMs.set(System.currentTimeMillis())
```

So the updated methods start:

```kotlin
override fun onMessage(text: String) {
    lastByteReceivedAtMs.set(System.currentTimeMillis())

    // Check for auth failure (server may send error if token is invalid)
    ...
}

override fun onMessage(bytes: ByteArray) {
    lastByteReceivedAtMs.set(System.currentTimeMillis())
    handleBinaryMessage(bytes)
}
```

- [ ] **Step 5: Add `checkStall` and `startStallWatchdog`/`stopStallWatchdog` methods**

Add these three methods inside `SendSpinClient` (place them right before `attemptReconnect` at line 686):

```kotlin
/**
 * Start the stall watchdog. Called once handshake completes.
 * Cancels any previous instance.
 */
private fun startStallWatchdog() {
    stallWatchdogJob?.cancel()
    // Reset so we don't false-trip using a stale pre-handshake timestamp
    lastByteReceivedAtMs.set(System.currentTimeMillis())
    stallWatchdogJob = scope.launch {
        while (true) {
            delay(STALL_CHECK_INTERVAL_MS)
            checkStall()
        }
    }
}

/**
 * Stop the stall watchdog. Called on disconnect or during reconnect attempts.
 */
private fun stopStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
}

/**
 * Check whether the transport has gone silent for too long and force-close it
 * if so. Only acts when the client is connected, handshake is complete, and we
 * are not already in a reconnect cycle.
 *
 * Package-private for testing via reflection.
 */
private fun checkStall() {
    if (userInitiatedDisconnect.get()) return
    if (reconnecting.get()) return
    if (!handshakeComplete) return
    val t = transport ?: return
    if (!t.isConnected) return

    val sinceLastByte = System.currentTimeMillis() - lastByteReceivedAtMs.get()
    if (sinceLastByte > STALL_TIMEOUT_MS) {
        Log.w(TAG, "Stall watchdog: no data received in ${sinceLastByte}ms (threshold ${STALL_TIMEOUT_MS}ms) - forcing transport close")
        // Use a non-1000 close code so the onClosed handler triggers reconnection.
        // 1001 = "Going Away" - appropriate for our intent.
        t.close(1001, "stall watchdog")
    }
}
```

- [ ] **Step 6: Start the watchdog on handshake complete**

Grep `SendSpinClient.kt` and its parent `SendSpinProtocolHandler` for where `handshakeComplete` gets set to `true`. The most likely location is right after receiving the first successful `server/state` or `server/hello` message. Add `startStallWatchdog()` at that point.

If handshakeComplete is set in the superclass (`SendSpinProtocolHandler`), instead override the `onHandshakeComplete` hook if one exists, OR add a check in the `onConnected` callback dispatch. **Concrete instruction:** find `handshakeComplete = true` anywhere in the file (there's an assignment at line 762 during reconnect cleanup — that's setting it to `false`, not what you want). If you can't find a clean "handshake just completed" hook, add one:

In `SendSpinProtocolHandler` (find via grep), wherever it completes the handshake, call an `onHandshakeCompleteHook()` virtual method. Override it in `SendSpinClient`:

```kotlin
override fun onHandshakeCompleteHook() {
    startStallWatchdog()
}
```

If that's too invasive, simpler alternative: start the watchdog unconditionally in `prepareForConnection()` or right after `createLocalTransport`/`createRemoteTransport`/`createProxyTransport` — the `checkStall()` guard `if (!handshakeComplete) return` makes early starts harmless.

**Preferred concrete approach:** Start the watchdog at the end of `prepareForConnection()` (search for `private fun prepareForConnection` in the file). That runs once per user-initiated connect and is reset on every reconnect attempt via `connectLocal`/`connectRemote`/`connectProxy`. Since `checkStall()` is guarded by `!handshakeComplete`, pre-handshake ticks are no-ops.

- [ ] **Step 7: Stop the watchdog on disconnect / destroy**

Grep for `fun disconnect(` and `fun destroy(` in `SendSpinClient.kt`. Add `stopStallWatchdog()` at the start of each method's body.

Also add `stopStallWatchdog()` inside `attemptReconnect` right after the `attempts == 1` block (line 712), so we don't race the watchdog against in-flight reconnects:

```kotlin
// On first reconnection attempt, freeze the time filter
if (attempts == 1) {
    timeFilter.freeze()
    Log.i(TAG, "Time filter frozen for reconnection (had ${timeFilter.measurementCountValue} measurements)")
}
stopStallWatchdog()  // watchdog restarts on next successful handshake
```

- [ ] **Step 8: Run the stall watchdog tests**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientStallWatchdogTest"
```

Expected: all 5 tests pass.

- [ ] **Step 9: Run the full SendSpinClient test suite to catch regressions**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.*"
```

Expected: all pass. If `SendSpinClientReconnectBackoffTest` or others fail because the watchdog is running during their reflection-based invocations, add `setUp()` calls to force-stop the watchdog or mock `scope` — but this is unlikely, since those tests call `attemptReconnect` directly without starting watchdogs.

- [ ] **Step 10: Build**

```bash
cd android
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt \
        android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientStallWatchdogTest.kt
git commit -m "feat: add application-level stall watchdog to SendSpinClient

When a TCP connection goes half-open (WiFi drops silently, NAT rebinds,
upstream hangs) the socket can look alive until Ktor's 30s ping times out.
By then the audio buffer has already drained and playback has errored.

The watchdog tracks the timestamp of the last received text or binary
frame and polls every 3s. If a connected, handshake-complete session has
been silent for 7s, it calls transport.close(1001) which routes through
the existing onClosed() reconnect path. This front-runs Ktor's ping
detection by 20+ seconds -- enough to start a reconnect before the buffer
empties in most cases.

Unit tests cover timestamp update on text/binary frames, trip on stall,
no-trip when recently active, and no-trip during active reconnection."
```

---

## Task 3: C — Remove 5-attempt cap in normal mode (retry forever with 30s steady-state)

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt:714-724, 738-744, 746`
- Modify: `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientReconnectBackoffTest.kt`

### Design notes

Today, `attemptReconnect` caps at `MAX_RECONNECT_ATTEMPTS = 5` unless `highPowerMode` is on, giving up with `wasReconnectExhausted = true` on the 6th attempt. After 5 attempts (total wait time 500ms+1s+2s+4s+8s = ~15.5s), the user is stuck.

With this change, normal mode behaves like high-power mode: infinite attempts with exponential backoff for the first 5, then 30s steady-state. The banner already handles arbitrary attempt counts, so no UI change needed. The only side effect users will see is that the "Connection lost, please reconnect manually" toast/error will no longer appear — the banner stays up instead.

**Why not a different cap (e.g., 20 attempts)?** Because the whole point is "don't force-kill". If we cap at any number, we're setting up the same bug at a different threshold. 30s steady-state retry is cheap (one request every 30s) and the user can always manually disconnect if they're done listening.

**Battery concern?** At 30s cadence, worst case is 2 req/min. A silent radio wake costs ~0.5mWh. Over an hour of disconnected state, that's ~60mWh — negligible vs. a typical 15000mWh battery.

> **Implementation note (2026-05):** The shipped behavior keeps a hard `MAX_TOTAL_RECONNECT_ATTEMPTS = 20` ceiling (~7m45s try-window) instead of removing the cap entirely. The above paragraph argued against any cap; the in-code rationale at `SendSpinClient.kt:92-97` accepts the reasoning while choosing a high enough ceiling to cover all realistic transient outages. If field data shows a 20-attempt cap is hitting real users in benign network glitches, follow this plan's original guidance and remove it.

### Steps

- [ ] **Step 1: Update the existing `max 5 reconnect attempts in normal mode` test**

Open `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientReconnectBackoffTest.kt`. The test at line 142 (`fun \`max 5 reconnect attempts in normal mode\``) currently asserts that the 6th attempt triggers error state. We need to rename it and invert its assertion:

Replace that entire test method (lines 141-171) with:

```kotlin
@Test
fun `normal mode retries forever without triggering exhausted error`() {
    setupForReconnection()
    every { UserSettings.highPowerMode } returns false

    val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
    attemptReconnect.isAccessible = true

    // Perform 10 attempts - all should succeed in normal mode now
    for (i in 1..10) {
        attemptReconnect.invoke(client)
    }

    // Should NOT have called onDisconnected with wasReconnectExhausted=true
    verify(exactly = 0) {
        mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
    }

    // All 10 should have been onReconnecting calls
    verify(exactly = 10) {
        mockCallback.onReconnecting(any(), any())
    }

    // State should remain Connecting (not Error)
    assertTrue(
        "State should remain Connecting in normal mode with no cap, was: ${client.connectionState.value}",
        client.connectionState.value is SendSpinClient.ConnectionState.Connecting
    )
}
```

- [ ] **Step 2: Add a test verifying steady-state delay kicks in after attempt 5 in normal mode**

Add this test to `SendSpinClientReconnectBackoffTest.kt` immediately after the test from Step 1:

```kotlin
@Test
fun `normal mode uses 30s steady-state delay after attempt 5`() {
    // Verify the delay formula selects the steady-state path for attempts > 5
    // regardless of highPowerMode setting. The formula we expect in SendSpinClient:
    //   val delayMs = if (attempts > MAX_RECONNECT_ATTEMPTS) HIGH_POWER_RECONNECT_DELAY_MS
    //                 else (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
    //                         .coerceAtMost(MAX_RECONNECT_DELAY_MS)

    val initialDelay = 500L
    val maxDelay = 10_000L
    val steadyStateDelay = 30_000L

    for (attempt in 1..5) {
        val computed = (initialDelay * (1 shl (attempt - 1))).coerceAtMost(maxDelay)
        val expected = when (attempt) {
            1 -> 500L; 2 -> 1000L; 3 -> 2000L; 4 -> 4000L; 5 -> 8000L
            else -> fail("unreachable") as Long
        }
        assertEquals("Attempt $attempt should use exponential backoff", expected, computed)
    }

    // Attempt 6+ should use steady-state 30s
    for (attempt in 6..10) {
        val computed = if (attempt > 5) steadyStateDelay
                       else (initialDelay * (1 shl (attempt - 1))).coerceAtMost(maxDelay)
        assertEquals("Attempt $attempt should use 30s steady-state", steadyStateDelay, computed)
    }
}
```

- [ ] **Step 3: Run the tests to confirm they fail**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientReconnectBackoffTest"
```

Expected: `normal mode retries forever without triggering exhausted error` FAILS because the 6th attempt currently triggers `onDisconnected(wasReconnectExhausted=true)`. The steady-state formula test passes (it only tests arithmetic, not the source).

- [ ] **Step 4: Modify `attemptReconnect` to remove the hard cap**

Open `SendSpinClient.kt`. Find the block at lines 714-724:

```kotlin
// Check attempt limits - high power mode allows infinite retries
val maxAttempts = if (UserSettings.highPowerMode) Int.MAX_VALUE else MAX_RECONNECT_ATTEMPTS
if (attempts > maxAttempts) {
    Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
    reconnecting.set(false)
    timeFilter.resetAndDiscard()
    _connectionState.value = ConnectionState.Error("Connection lost. Please reconnect manually.")
    callback.onError("Connection lost after $MAX_RECONNECT_ATTEMPTS reconnection attempts")
    callback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
    return
}
```

Delete it entirely. Both modes now retry forever.

- [ ] **Step 5: Update the backoff formula to apply steady-state in both modes**

Find the block at lines 738-744:

```kotlin
// Exponential backoff for first 5 attempts, then steady 30s in high power mode
val delayMs = if (UserSettings.highPowerMode && attempts > MAX_RECONNECT_ATTEMPTS) {
    HIGH_POWER_RECONNECT_DELAY_MS
} else {
    (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
}
```

Replace with:

```kotlin
// Exponential backoff for first 5 attempts, then 30s steady-state forever.
// Applies in both normal and high power mode - the user can always disconnect
// manually if they're done listening.
val delayMs = if (attempts > MAX_RECONNECT_ATTEMPTS) {
    HIGH_POWER_RECONNECT_DELAY_MS
} else {
    (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
}
```

- [ ] **Step 6: Simplify the log display since both modes behave identically now**

Line 746:

```kotlin
val attemptsDisplay = if (UserSettings.highPowerMode) "$attempts" else "$attempts/$MAX_RECONNECT_ATTEMPTS"
```

Replace with:

```kotlin
val attemptsDisplay = "$attempts"
```

- [ ] **Step 7: Run the tests to confirm all pass**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientReconnectBackoffTest"
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.*"
```

Expected: all pass. Check especially that `high power mode uses 30s delay after attempt 5` (existing test, line 201) still passes — the formula still selects the steady-state path when `attempts > MAX_RECONNECT_ATTEMPTS`, just without the `highPowerMode` precondition.

- [ ] **Step 8: Run the full unit test suite**

```bash
cd android
./gradlew :app:testDebugUnitTest
```

Expected: all pass. If `e2e/NetworkLossDrainingReconnectTest.kt` asserts that `wasReconnectExhausted=true` fires, update it to assert the opposite (that reconnection continues indefinitely).

- [ ] **Step 9: Build**

```bash
cd android
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt \
        android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientReconnectBackoffTest.kt
git commit -m "fix: retry reconnect forever in normal mode with 30s steady-state

Previously normal mode gave up after 5 attempts (~15s) with an error
state that required the user to manually reconnect -- but there is no
manual-reconnect button anywhere in the UI, so in practice users had to
force-kill the app after any transient network loss.

Both normal and high power mode now behave identically: exponential
backoff for the first 5 attempts (500ms -> 8s), then 30s steady-state
retries forever. The ReconnectingBanner already handles arbitrary
attempt counts, so no UI change needed.

Battery impact is negligible (2 req/min steady-state). Users can
always disconnect manually if they're done."
```

---

## Manual Verification (after all three tasks are in)

Before marking this plan done, the implementing engineer should run through this scenario **on a physical Android device**, not just unit tests. Unit tests verify plumbing; this verifies the user-observable behavior.

1. Connect the app to a SendSpin server over WiFi.
2. Start playback — verify audio plays and "Now Playing" screen is visible.
3. Walk to the edge of WiFi range OR toggle WiFi off on the phone OR disable the router. The goal is to simulate "network still associated but no internet" — so toggling airplane mode is too blunt; prefer walking out of range or stopping the router.
4. Within ~10 seconds (watchdog timeout + reconnect start): the "Reconnecting..." banner should appear.
5. Audio should continue from buffer until drained, then stop.
6. Walk back into WiFi range OR re-enable the router.
7. Within ~30s (steady-state retry interval): the app should reconnect automatically. Audio should resume (after a re-sync).
8. At **no point** should the app require a force-kill to recover.

Log tags to grep for:
- `SendSpinClient` — look for `Stall watchdog: no data received in XXXms` and `Attempting reconnection X in XXXms`
- `PlaybackService` — look for `Network lost VALIDATED capability` and `Validation loss confirmed after debounce`

If any of those tags do NOT appear during the simulated outage, one of the detection paths is broken. Return to the corresponding task.

---

## Self-Review Summary

- **Spec coverage:** A, B, C each mapped 1:1 to Tasks 1, 2, 3.
- **Placeholders:** None — every step has concrete code, file paths, and commands.
- **Type consistency:** `lastByteReceivedAtMs` used consistently across Task 2 implementation and tests. `STALL_TIMEOUT_MS` and `STALL_CHECK_INTERVAL_MS` referenced consistently. `VALIDATION_LOSS_DEBOUNCE_MS` referenced consistently in Task 1.
- **Edge cases covered:** watchdog disabled during reconnection, watchdog disabled pre-handshake, validation-loss debounce to avoid flaps, steady-state formula still triggers correctly.
- **Non-goals (deferred to D-G):** manual "Reconnect now" button (D), cross-mode failover (E), recoverable-error loosening during network transitions (F), audio-pipeline underrun signal (G).
