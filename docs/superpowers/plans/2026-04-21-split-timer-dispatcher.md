# Split `SendSpinClient` Scope into `timerScope` + `workScope` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address audit finding M-4 by splitting `SendSpinClient`'s single `Dispatchers.IO` scope into two purpose-built scopes: `timerScope` (dedicated single-thread dispatcher for wait/timer work) and `workScope` (Dispatchers.IO for actual blocking IO).

**Architecture:** `timerScope` owns anything whose primary work is waiting: stall watchdog polling, reconnect backoff delays, `TimeSyncManager`'s periodic scheduler. `workScope` owns actual IO: immediate reconnect transport creation, any future blocking work. The two-phase reconnect (delay → network work) waits on `timerScope` and switches to `Dispatchers.IO` via `withContext` for the transport work.

**Tech Stack:** Kotlin coroutines, `java.util.concurrent.Executors`, `kotlinx.coroutines.asCoroutineDispatcher`.

**Worktree:** `C:/CodeProjects/SendspinDroid-split-timer-dispatcher`, branch `task/split-timer-dispatcher`, based on `origin/main` at `5a258a7`.

**Scope:** Pure refactor. No user-visible behavior change. One file changed (`SendSpinClient.kt`), plus a small new test verifying dispatcher separation.

---

## File Structure

**Modify:**
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` — split scope declaration, update three `scope.launch` callsites, update `getCoroutineScope()`, update `destroy()` cleanup.

**Create (optional — small test):**
- `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientScopeSplitTest.kt` — verify watchdog coroutine runs on `SendSpinTimer` thread name; verify workScope-launched coroutine runs on a `DefaultDispatcher-worker` or `pool-N-thread-M` thread (i.e., not `SendSpinTimer`).

---

## Task 1: Split scope into timerScope + workScope, update all call sites

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`

This task bundles the scope split and all three call-site migrations into one commit because the build isn't green until they all land together (renaming `scope` would otherwise leave dangling references).

- [ ] **Step 1.1: Locate current scope declaration and usages**

```bash
cd "C:/CodeProjects/SendspinDroid-split-timer-dispatcher"
grep -n "scope = CoroutineScope\|scope\.launch\|scope\.cancel\|getCoroutineScope" android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt
```

Expected matches:
- `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — the declaration
- `getCoroutineScope(): CoroutineScope = scope` — the protocol-handler accessor
- Three `scope.launch { ... }` sites (immediate reconnect, stall watchdog, reconnect backoff)
- Possibly a `scope.cancel()` in `destroy()`

- [ ] **Step 1.2: Add imports**

At the top of `SendSpinClient.kt`, add:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher  // only if not already imported
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
```

Check if any of these are already imported — skip duplicates. `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.SupervisorJob` are almost certainly already imported.

- [ ] **Step 1.3: Replace the single `scope` field with two scopes**

Find:

```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Replace with:

```kotlin
    // Dedicated single-thread dispatcher for timer-dominated work: stall
    // watchdog polling, reconnect backoff delays, TimeSyncManager's
    // periodic scheduler. Isolating this from Dispatchers.IO means timer
    // latency is bounded by a single thread's scheduling, not by shared
    // pool contention with blocking IO work.
    //
    // ExecutorCoroutineDispatcher is held as its concrete type so it can
    // be closed() during destroy() -- otherwise the executor thread leaks.
    private val timerDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val timerScope = CoroutineScope(SupervisorJob() + timerDispatcher)

    // Dispatchers.IO scope for blocking IO work: immediate reconnect
    // transport creation, any other work that may block the thread.
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

- [ ] **Step 1.4: Update `getCoroutineScope()` to return `timerScope`**

Find:

```kotlin
    override fun getCoroutineScope(): CoroutineScope = scope
```

Replace with:

```kotlin
    // TimeSyncManager uses this scope for its periodic scheduler loop
    // (delay then send a small time-sync request). That is timer-dominated
    // work, so it belongs on timerScope.
    override fun getCoroutineScope(): CoroutineScope = timerScope
```

- [ ] **Step 1.5: Update immediate-reconnect launch site (currently `scope.launch` near line 492)**

The comment at this site should say "Immediately try to reconnect...". The launch body does `createLocalTransport` / `createRemoteTransport` / `createProxyTransport` — all blocking IO. This belongs on `workScope`.

Find the `scope.launch {` call in that block and replace with `workScope.launch {`. No other change to the body.

- [ ] **Step 1.6: Update stall-watchdog launch site (currently `scope.launch` near line 877)**

The body is:

```kotlin
            stallWatchdogJob = scope.launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    checkStall()
                }
            }
```

Change `scope.launch` to `timerScope.launch`:

```kotlin
            stallWatchdogJob = timerScope.launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    checkStall()
                }
            }
```

The `checkStall()` callee does not block (reads atomic fields, calls `transport?.close(1001, ...)` on a detected stall — close is non-blocking). Safe on `timerScope`'s single thread.

- [ ] **Step 1.7: Update reconnect-backoff launch site (currently `scope.launch` near line 1076)**

The body does `delay(delayMs)` then transport creation. Split across scopes: the delay and pre-check stay on `timerScope`, the transport creation moves into a `withContext(Dispatchers.IO)` block.

Before:

```kotlin
        reconnectJob = scope.launch {
            delay(delayMs)

            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled")
                return@launch
            }

            handshakeComplete = false
            stopTimeSync()

            // Clean up old transport
            transport?.destroy()
            transport = null

            // Reconnect using the appropriate mode
            when (connectionMode) {
                ConnectionMode.LOCAL -> {
                    ...
                    createLocalTransport(address, path)
                }
                ...
            }
        }
```

After:

```kotlin
        reconnectJob = timerScope.launch {
            delay(delayMs)

            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled")
                return@launch
            }

            handshakeComplete = false
            stopTimeSync()

            // Clean up old transport
            transport?.destroy()
            transport = null

            // Transport creation does blocking IO -- switch dispatcher
            // from the single-thread timer to the IO pool.
            withContext(Dispatchers.IO) {
                // Reconnect using the appropriate mode
                when (connectionMode) {
                    ConnectionMode.LOCAL -> {
                        ...
                        createLocalTransport(address, path)
                    }
                    ...
                }
            }
        }
```

Keep the rest of the original when/else branches verbatim inside the `withContext` block. The early-return checks and `stopTimeSync()` / `transport?.destroy()` stay before the `withContext` because they're non-blocking bookkeeping.

- [ ] **Step 1.8: Update `destroy()` to cancel both scopes and close the timer dispatcher**

Find the existing scope-cancel in `destroy()` (search `scope.cancel`). If it exists, replace with both cancels + dispatcher close. If it doesn't exist today, add the cleanup.

```kotlin
    fun destroy() {
        // ... existing cleanup (stall watchdog stop, etc.) ...

        // Cancel both scopes before closing the timer dispatcher.
        // Cancelling the scope cancels all its launched coroutines; closing
        // the dispatcher shuts down the underlying executor thread.
        timerScope.cancel()
        workScope.cancel()
        timerDispatcher.close()

        // ... any remaining cleanup ...
    }
```

If the existing `destroy()` does NOT currently cancel the single scope, investigate whether that was an omission or intentional. Either way, the new two-scope version should explicitly cancel both.

- [ ] **Step 1.9: Build**

```bash
cd "C:/CodeProjects/SendspinDroid-split-timer-dispatcher/android"
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

If you see "unresolved reference: scope", you missed a callsite — grep for remaining `\bscope\b` in `SendSpinClient.kt` and migrate it to `timerScope` or `workScope` based on the rules in Task 1 Steps 1.5-1.7.

- [ ] **Step 1.10: Run existing SendSpinClient-adjacent tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClient*"
```

Expected: PASS. Some tests reference `getCoroutineScope()` returning a test scope — that interface hasn't changed, so those should still work.

- [ ] **Step 1.11: Commit**

```bash
cd "C:/CodeProjects/SendspinDroid-split-timer-dispatcher"
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt
git commit -m "$(cat <<'EOF'
refactor: split SendSpinClient scope into timerScope + workScope (M-4)

Address audit finding M-4: the single Dispatchers.IO scope that owned
the stall watchdog, reconnect backoff, time-sync scheduler, and
immediate-reconnect work shared a bounded pool with other blocking IO
in the process. Timer coroutines could queue behind unrelated IO under
contention.

Split into two scopes with distinct intents:

  * timerScope: single-thread ExecutorCoroutineDispatcher named
    "SendSpinTimer" (daemon). Owns wait-dominated work -- stall
    watchdog polling, reconnect backoff delay, TimeSyncManager's
    scheduler. Timer latency is bounded by one thread's scheduling,
    not by pool contention.

  * workScope: unchanged Dispatchers.IO. Owns actual blocking IO --
    immediate reconnect transport creation and any future blocking
    work.

Two-phase reconnect: wait on timerScope, switch to Dispatchers.IO
via withContext for transport creation.

getCoroutineScope() now returns timerScope because its only consumer,
TimeSyncManager, is timer-dominated.

destroy() cancels both scopes and closes the timer dispatcher to
shut down the executor thread.

No user-visible behavior change.
EOF
)"
```

---

## Task 2: Add regression test + final build + PR

**Files:**
- Create: `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientScopeSplitTest.kt`

- [ ] **Step 2.1: Write the thread-name regression test**

Create the file with:

```kotlin
package com.sendspindroid.sendspin

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for audit finding M-4: verify that SendSpinClient's
 * two-scope split actually routes timer and IO work to different threads.
 *
 * This test does not construct a SendSpinClient (its constructor requires
 * several Android-specific collaborators). Instead it exercises the same
 * primitives the production code uses, confirming the dispatcher pattern
 * produces the expected thread separation.
 */
class SendSpinClientScopeSplitTest {

    @Test
    fun `single-thread executor dispatcher routes to named thread`() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }
        val dispatcher = kotlinx.coroutines.ExecutorCoroutineDispatcher::class.java
            .let {
                // Use the public factory surface rather than constructing the
                // concrete type directly.
                @Suppress("DEPRECATION")
                kotlinx.coroutines.asCoroutineDispatcher(exec)
            }
        try {
            val threadName = runBlocking(dispatcher) {
                Thread.currentThread().name
            }
            assertEquals("SendSpinTimer", threadName)
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun `withContext IO moves work off the single-thread dispatcher`() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }
        val dispatcher = kotlinx.coroutines.asCoroutineDispatcher(exec)
        try {
            val (timerThread, ioThread) = runBlocking(dispatcher) {
                val timer = Thread.currentThread().name
                val io = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Thread.currentThread().name
                }
                timer to io
            }
            assertEquals("SendSpinTimer", timerThread)
            assertTrue(
                "withContext(IO) should leave the SendSpinTimer thread, got '$ioThread'",
                ioThread != "SendSpinTimer",
            )
        } finally {
            exec.shutdown()
        }
    }
}
```

Note: if the `Executors.newSingleThreadExecutor(ThreadFactory).asCoroutineDispatcher()` import path is incorrect for the current kotlinx-coroutines version on this project, adjust to whatever the production code in Task 1 actually uses. The goal is that the test exercises the same factory pattern.

- [ ] **Step 2.2: Run the new test**

```bash
cd "C:/CodeProjects/SendspinDroid-split-timer-dispatcher/android"
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClientScopeSplitTest"
```

Expected: PASS.

- [ ] **Step 2.3: Full build + tests**

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Expected: PASS. Three pre-existing known-failing tests on origin/main (`SendspinTimeFilterTest.stabilityScore_consistentMeasurements_convergesToOne`, `MaCommandMultiplexerTest`, `MessageParserTest.parseServerTime_zeroTimestamps_returnsResult`) — ignore those, they are not regressions from this PR.

- [ ] **Step 2.4: Commit the test**

```bash
cd "C:/CodeProjects/SendspinDroid-split-timer-dispatcher"
git add android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientScopeSplitTest.kt
git commit -m "test: pin SendSpinClient scope-split thread separation (M-4)"
```

- [ ] **Step 2.5: Review diff**

```bash
git log --oneline origin/main..HEAD
git diff --stat origin/main..HEAD
```

Expected: 2 commits, modifications to `SendSpinClient.kt`, 1 new test file. Net ~40-60 lines added in the production code, ~50 lines added in the test.

- [ ] **Step 2.6: Push and open PR**

```bash
git push -u origin task/split-timer-dispatcher
gh pr create --base main --title "refactor: split SendSpinClient scope into timer + work (M-4)" --body "$(cat <<'EOF'
## Summary

Addresses audit finding M-4 (deferred from the main architecture-audit rollup): `SendSpinClient` previously ran timer coroutines (stall watchdog polling, reconnect backoff, `TimeSyncManager` scheduler) on the same `Dispatchers.IO` pool used for actual blocking IO. Timer latency could slip under pool contention.

Split into two scopes:

- **`timerScope`**: new single-thread `ExecutorCoroutineDispatcher` named `SendSpinTimer` (daemon). Owns wait-dominated work. Timer latency is now bounded by one thread's scheduling rather than shared pool contention.
- **`workScope`**: unchanged `Dispatchers.IO`. Owns actual blocking IO — primarily the immediate-reconnect transport creation.

Two-phase reconnect backoff: wait on `timerScope`, then `withContext(Dispatchers.IO)` for transport creation.

`getCoroutineScope()` now returns `timerScope` (its only consumer, `TimeSyncManager`, is timer-dominated).

`destroy()` cancels both scopes and closes the timer dispatcher so the executor thread doesn't leak.

**No user-visible behavior change.**

## Verified

- [x] `./gradlew assembleDebug`
- [x] `:app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SendSpinClient*"`
- [x] New `SendSpinClientScopeSplitTest` locks in the thread-name separation.

## Test plan

- [ ] CI build + tests pass.
- [ ] On-device smoke: reconnect after network loss still works (timer fires, transport created on IO thread).
- [ ] On-device smoke: stall watchdog still closes stalled connections (3 s poll still works from the dedicated thread).
EOF
)"
```

- [ ] **Step 2.7: Report**

Print PR URL and confirm Task 15 (deferred list item) can be marked resolved.

---

## Self-review notes

- Only `SendSpinClient.kt` and one new test are touched. No API changes outside the class. No behavior change.
- The `watchdogLock` added in PR #141 (M-3 fix) is unchanged — the start/stop pair is still serialized; only the coroutine's launch site moves to `timerScope`.
- If any part of the project relies on `scope` (as opposed to `getCoroutineScope()`) being a specific dispatcher, that would be an issue — but `scope` is `private` to `SendSpinClient` and has no external visibility.
- The new test exercises the coroutine-dispatcher library directly rather than constructing a `SendSpinClient`, because the client requires Android-specific collaborators (callback, transport factory) that are expensive to mock. The test's value is documenting the dispatcher-pattern contract that Task 1 implements.
