package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.AdaptiveBufferPolicy.SyncQuality
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic tests for [AdaptiveBufferPolicy]. Time is injected as `nowMs`,
 * so cooldown/streak behaviour is fully reproducible without real time or audio.
 *
 * The mechanics tests use [lean] (small, clear numbers) so step-by-step shrink
 * behaviour is easy to read; separate tests pin the production [generous] and
 * [lowMemory] profiles.
 */
class AdaptiveBufferPolicyTest {

    /** Small config so shrink steps are easy to follow (floor 250, initial 500). */
    private fun lean() = AdaptiveBufferPolicy.Config(
        floorMs = 250,
        ceilingMs = 4_000,
        initialMs = 500,
        shrinkCooldownMs = 30_000,
        shrinkStepMs = 100,
    )

    private fun policy(config: AdaptiveBufferPolicy.Config = AdaptiveBufferPolicy.Config()) =
        AdaptiveBufferPolicy(config)

    /** Feed a good (low-RTT, synchronized) measurement. */
    private fun AdaptiveBufferPolicy.good(nowMs: Long, rttMs: Double = 10.0) =
        update(nowMs, rttMs, SyncQuality.GOOD)

    // --- production profiles ---

    @Test
    fun `default config is generous`() {
        assertEquals(1_500, policy().currentTargetMs)
    }

    @Test
    fun `generous good-link steady state stays at the generous floor`() {
        val p = policy(AdaptiveBufferPolicy.generous())
        var t = 0L
        repeat(10) { p.good(t); t += 60_000 }
        assertEquals(1_500, p.currentTargetMs) // never trims below the generous baseline
    }

    @Test
    fun `generous grows toward the ceiling under sustained trouble`() {
        val p = policy(AdaptiveBufferPolicy.generous())
        var t = 0L
        repeat(20) {
            p.update(t, 4_000.0, SyncQuality.LOST, underrun = true)
            t += 3_000
        }
        assertEquals(5_000, p.currentTargetMs)
    }

    @Test
    fun `lowMemory profile is smaller than generous`() {
        val low = policy(AdaptiveBufferPolicy.lowMemory())
        val gen = policy(AdaptiveBufferPolicy.generous())
        assertTrue(low.currentTargetMs < gen.currentTargetMs)
        // Good link settles at the low-memory floor.
        var t = 0L
        repeat(10) { low.good(t); t += 60_000 }
        assertEquals(500, low.currentTargetMs)
    }

    // --- mechanics (lean config) ---

    @Test
    fun `initial target is the configured initial value`() {
        assertEquals(500, policy(lean()).currentTargetMs)
    }

    @Test
    fun `sustained good link shrinks slowly toward the floor`() {
        val p = policy(lean())
        p.good(0)               // establishes the good streak (no shrink yet)
        assertEquals(500, p.currentTargetMs)
        p.good(60_000)          // 60s sustained -> first shrink
        assertEquals(400, p.currentTargetMs)
        p.good(90_000)          // +30s cooldown -> shrink
        assertEquals(300, p.currentTargetMs)
        p.good(120_000)         // -> floor
        assertEquals(250, p.currentTargetMs)
        p.good(150_000)         // already at floor -> stable
        assertEquals(250, p.currentTargetMs)
    }

    @Test
    fun `does not shrink before the sustained-good window elapses`() {
        val p = policy(lean())
        p.good(0)
        p.good(59_000) // < 60s
        assertEquals(500, p.currentTargetMs)
    }

    @Test
    fun `underrun bumps the target up`() {
        val p = policy(lean())
        p.good(0)
        val before = p.currentTargetMs
        val after = p.update(100, 10.0, SyncQuality.GOOD, underrun = true)
        assertTrue("underrun should grow the buffer ($before -> $after)", after > before)
    }

    @Test
    fun `grow cooldown limits consecutive bumps`() {
        val p = policy(lean())
        p.good(0)
        val t1 = p.update(100, 10.0, SyncQuality.GOOD, underrun = true)
        val t2 = p.update(200, 10.0, SyncQuality.GOOD, underrun = true) // within 2s cooldown
        assertEquals("no second bump within cooldown", t1, t2)
        val t3 = p.update(2_200, 10.0, SyncQuality.GOOD, underrun = true) // past cooldown
        assertTrue("bump allowed after cooldown ($t2 -> $t3)", t3 > t2)
    }

    @Test
    fun `rtt spike grows the target`() {
        val p = policy(lean())
        p.good(0, 50.0)
        p.good(1_000, 50.0)
        p.good(2_000, 50.0) // baseline ~50
        val before = p.currentTargetMs
        val after = p.update(3_000, 600.0, SyncQuality.GOOD) // 600 >> 50*1.5
        assertTrue("rtt spike should grow ($before -> $after)", after > before)
    }

    @Test
    fun `lost sync yields a larger target than good at the same rtt and jitter`() {
        // Identical RTT sequence; the second sample (past the grow cooldown) is a
        // spike so both policies grow to their ideal. LOST's 2x jitter multiplier
        // makes its ideal larger.
        val lost = policy(lean())
        lost.update(0, 300.0, SyncQuality.LOST)
        val lostTarget = lost.update(3_000, 500.0, SyncQuality.LOST)

        val good = policy(lean())
        good.update(0, 300.0, SyncQuality.GOOD)
        val goodTarget = good.update(3_000, 500.0, SyncQuality.GOOD)

        assertTrue("LOST ($lostTarget) should exceed GOOD ($goodTarget)", lostTarget > goodTarget)
    }

    @Test
    fun `shrink cooldown limits consecutive shrinks`() {
        val p = policy(lean())
        p.good(0)
        p.good(60_000)
        assertEquals(400, p.currentTargetMs) // first shrink
        p.good(70_000) // only 10s later -> still in 30s cooldown
        assertEquals(400, p.currentTargetMs)
        p.good(90_000) // 30s after the shrink
        assertEquals(300, p.currentTargetMs)
    }

    @Test
    fun `target never exceeds the ceiling`() {
        val p = policy(lean().copy(ceilingMs = 2_000))
        var t = 0L
        repeat(10) {
            p.update(t, 5_000.0, SyncQuality.LOST, dropRate = 1.0, underrun = true)
            t += 3_000
        }
        assertEquals(2_000, p.currentTargetMs)
    }

    @Test
    fun `target never drops below the floor`() {
        val p = policy(lean())
        var t = 0L
        repeat(50) {
            p.good(t)
            t += 60_000
            assertTrue("never below floor", p.currentTargetMs >= 250)
        }
        assertEquals(250, p.currentTargetMs)
    }

    @Test
    fun `jitter reflects rtt variance`() {
        val p = policy(lean())
        p.update(0, 10.0, SyncQuality.GOOD)
        p.update(1, 30.0, SyncQuality.GOOD)
        p.update(2, 10.0, SyncQuality.GOOD)
        p.update(3, 30.0, SyncQuality.GOOD)
        // stddev of [10,30,10,30] = sqrt(400/3) ~= 11.55
        assertEquals(11.55, p.jitterMs, 0.2)
    }

    @Test
    fun `no oscillation once converged on a steady good link`() {
        val p = policy(lean())
        var t = 0L
        repeat(5) { p.good(t); t += 60_000 } // converge to the floor
        assertEquals(250, p.currentTargetMs)
        val seq = listOf(8.0, 12.0, 9.0, 15.0, 7.0, 11.0, 10.0, 13.0)
        for (rtt in seq) {
            t += 1_000
            p.good(t, rtt)
            assertEquals("must stay pinned at floor", 250, p.currentTargetMs)
        }
    }

    @Test
    fun `a troubled link does not shrink`() {
        val p = policy(lean())
        p.good(0)
        p.update(100, 20.0, SyncQuality.GOOD, dropRate = 0.2)
        assertEquals("a troubled link must not shrink", 500, p.currentTargetMs)
    }
}
