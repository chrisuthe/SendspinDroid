package com.sendspindroid.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for DefaultServerPinger.calculateNextInterval() fix (M-08).
 *
 * Verifies that the backoff interval never drops below the base interval
 * for the current foreground/charging state. Previously, a background-mode
 * pinger with 1 failure would use 60s backoff instead of the 120s background
 * base interval.
 */
class DefaultServerPingerIntervalTest {

    companion object {
        // Mirror the constants from DefaultServerPinger
        private const val INTERVAL_FOREGROUND_MS = 60_000L
        private const val INTERVAL_BACKGROUND_MS = 120_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val INITIAL_BACKOFF_MS = 60_000L
    }

    /**
     * Reimplements the fixed calculateNextInterval logic for testing.
     * This mirrors the production code so we can verify the algorithm
     * without needing to instantiate DefaultServerPinger (which requires
     * a NetworkEvaluator with Android Context).
     */
    private fun calculateNextInterval(
        isInForeground: Boolean,
        isCharging: Boolean,
        consecutiveFailures: Int
    ): Long {
        val baseInterval = if (isInForeground || isCharging) {
            INTERVAL_FOREGROUND_MS
        } else {
            INTERVAL_BACKGROUND_MS
        }

        return if (consecutiveFailures > 0) {
            val backoff = INITIAL_BACKOFF_MS * (1L shl (consecutiveFailures - 1).coerceAtMost(4))
            maxOf(backoff, baseInterval).coerceAtMost(MAX_BACKOFF_MS)
        } else {
            baseInterval
        }
    }

    // ---- Foreground, no failures ----

    @Test
    fun `foreground with no failures returns foreground interval`() {
        val interval = calculateNextInterval(
            isInForeground = true,
            isCharging = false,
            consecutiveFailures = 0
        )
        assertEquals(INTERVAL_FOREGROUND_MS, interval)
    }

    // ---- Background, no failures ----

    @Test
    fun `background with no failures returns background interval`() {
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = false,
            consecutiveFailures = 0
        )
        assertEquals(INTERVAL_BACKGROUND_MS, interval)
    }

    // ---- The key M-08 fix: background with 1 failure ----

    @Test
    fun `background with 1 failure never drops below background interval`() {
        // Before fix: backoff = 60s (INITIAL_BACKOFF_MS * 2^0), which is < 120s background interval
        // After fix: max(60s, 120s) = 120s
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = false,
            consecutiveFailures = 1
        )
        assertTrue(
            "Background interval with 1 failure should be >= INTERVAL_BACKGROUND_MS ($INTERVAL_BACKGROUND_MS), was $interval",
            interval >= INTERVAL_BACKGROUND_MS
        )
        assertEquals(INTERVAL_BACKGROUND_MS, interval) // 120s, not 60s
    }

    // ---- Background with escalating failures ----

    @Test
    fun `background with 2 failures uses backoff since it exceeds base`() {
        // backoff = 60s * 2^1 = 120s, base = 120s -> max(120s, 120s) = 120s
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = false,
            consecutiveFailures = 2
        )
        assertEquals(120_000L, interval)
    }

    @Test
    fun `background with 3 failures uses backoff`() {
        // backoff = 60s * 2^2 = 240s, base = 120s -> max(240s, 120s) = 240s
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = false,
            consecutiveFailures = 3
        )
        assertEquals(240_000L, interval)
    }

    @Test
    fun `background with 4 failures uses backoff`() {
        // backoff = 60s * 2^3 = 480s -> capped at 300s
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = false,
            consecutiveFailures = 4
        )
        assertEquals(MAX_BACKOFF_MS, interval)
    }

    // ---- Foreground with failures ----

    @Test
    fun `foreground with 1 failure uses foreground base since backoff equals it`() {
        // backoff = 60s, base = 60s -> max(60s, 60s) = 60s
        val interval = calculateNextInterval(
            isInForeground = true,
            isCharging = false,
            consecutiveFailures = 1
        )
        assertEquals(INTERVAL_FOREGROUND_MS, interval)
    }

    @Test
    fun `foreground with 2 failures uses backoff`() {
        // backoff = 120s, base = 60s -> max(120s, 60s) = 120s
        val interval = calculateNextInterval(
            isInForeground = true,
            isCharging = false,
            consecutiveFailures = 2
        )
        assertEquals(120_000L, interval)
    }

    // ---- Charging in background behaves like foreground ----

    @Test
    fun `charging in background with 1 failure uses foreground interval`() {
        val interval = calculateNextInterval(
            isInForeground = false,
            isCharging = true,
            consecutiveFailures = 1
        )
        assertEquals(INTERVAL_FOREGROUND_MS, interval)
    }

    // ---- Max backoff cap ----

    @Test
    fun `backoff never exceeds MAX_BACKOFF_MS regardless of failure count`() {
        for (failures in 1..20) {
            val interval = calculateNextInterval(
                isInForeground = false,
                isCharging = false,
                consecutiveFailures = failures
            )
            assertTrue(
                "Interval for $failures failures ($interval) should be <= MAX_BACKOFF_MS ($MAX_BACKOFF_MS)",
                interval <= MAX_BACKOFF_MS
            )
        }
    }

    // ---- Invariant: interval never below base ----

    @Test
    fun `interval is always at least the base interval for all failure counts`() {
        for (failures in 0..20) {
            val bgInterval = calculateNextInterval(
                isInForeground = false,
                isCharging = false,
                consecutiveFailures = failures
            )
            assertTrue(
                "Background interval for $failures failures ($bgInterval) should be >= $INTERVAL_BACKGROUND_MS",
                bgInterval >= INTERVAL_BACKGROUND_MS
            )

            val fgInterval = calculateNextInterval(
                isInForeground = true,
                isCharging = false,
                consecutiveFailures = failures
            )
            assertTrue(
                "Foreground interval for $failures failures ($fgInterval) should be >= $INTERVAL_FOREGROUND_MS",
                fgInterval >= INTERVAL_FOREGROUND_MS
            )
        }
    }
}
