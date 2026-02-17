package com.sendspindroid.sendspin.protocol.timesync

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimeSyncManagerTest {

    private lateinit var timeFilter: SendspinTimeFilter
    private var sendCount = 0
    private lateinit var manager: TimeSyncManager

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        timeFilter = SendspinTimeFilter()
        sendCount = 0
        manager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendCount++ }
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Lifecycle ---

    @Test
    fun initialState_isNotRunning() {
        assertFalse(manager.isRunning)
    }

    @Test
    fun start_setsRunningTrue() = runTest {
        manager.start(this)
        assertTrue(manager.isRunning)
        manager.stop()
    }

    @Test
    fun stop_setsRunningFalse() = runTest {
        manager.start(this)
        manager.stop()
        assertFalse(manager.isRunning)
    }

    @Test
    fun start_whenAlreadyRunning_doesNotDoubleStart() = runTest {
        manager.start(this)
        val firstRunning = manager.isRunning
        manager.start(this)
        assertTrue(firstRunning)
        assertTrue(manager.isRunning)
        manager.stop()
    }

    // --- onServerTime (outside burst) ---

    @Test
    fun onServerTime_outsideBurst_feedsToFilter() = runTest {
        manager.start(this)
        // Advance past the initial burst (10 packets * 50ms + 100ms wait = 600ms)
        advanceTimeBy(700)

        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 1_000_000L
        )

        val collected = manager.onServerTime(measurement)
        assertFalse("Should not report as collected (no burst)", collected)
        assertTrue("Should have fed to filter", timeFilter.measurementCountValue > 0)
        manager.stop()
    }

    @Test
    fun onServerTime_outsideBurst_staleRtt_isIgnored() = runTest {
        manager.start(this)
        advanceTimeBy(700)
        val countBefore = timeFilter.measurementCountValue

        val staleMeasurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 15_000_000L,
            clientReceived = 1_000_000L
        )

        val collected = manager.onServerTime(staleMeasurement)
        assertFalse(collected)
        assertEquals("Stale measurement should not be added", countBefore, timeFilter.measurementCountValue)
        manager.stop()
    }

    @Test
    fun onServerTime_outsideBurst_validRtt_acceptedByFilter() = runTest {
        manager.start(this)
        advanceTimeBy(700)

        val measurement = TimeMeasurement(
            offset = 50_000L,
            rtt = 20_000L,
            clientReceived = 1_000_000L
        )

        val countBefore = timeFilter.measurementCountValue
        manager.onServerTime(measurement)
        assertEquals(countBefore + 1, timeFilter.measurementCountValue)
        manager.stop()
    }

    // --- Burst behavior ---

    @Test
    fun start_sendsTimeSyncPackets() = runTest {
        manager.start(this)
        advanceTimeBy(1000)
        manager.stop()

        assertTrue("Should have sent time sync packets, sent $sendCount", sendCount > 0)
    }

    @Test
    fun stop_clearsBurstState() = runTest {
        manager.start(this)
        advanceTimeBy(100)
        manager.stop()

        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 2_000_000L
        )
        val collected = manager.onServerTime(measurement)
        assertFalse("After stop, should not collect for burst", collected)
    }

    @Test
    fun onServerTime_duringBurst_collectsForLater() = runTest {
        manager.start(this)
        advanceTimeBy(10)

        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 1_000_000L
        )
        val collected = manager.onServerTime(measurement)
        assertTrue("During burst, should collect for later", collected)

        manager.stop()
    }

    // --- C-03: burstInProgress stuck flag fix ---

    @Test
    fun stopMidBurst_thenRestart_onServerTimeStillWorks() = runTest {
        // Start and enter a burst
        manager.start(this)
        advanceTimeBy(10) // In the middle of the first burst

        // Verify we are mid-burst
        val midBurstMeasurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 1_000_000L
        )
        assertTrue("Should be mid-burst", manager.onServerTime(midBurstMeasurement))

        // Stop mid-burst
        manager.stop()
        assertFalse(manager.isRunning)

        // Restart
        manager.start(this)
        assertTrue(manager.isRunning)

        // Advance past the new burst so we are outside burst mode
        advanceTimeBy(700)

        // onServerTime should work normally (not stuck in burst collection mode)
        val measurement = TimeMeasurement(
            offset = 20_000L,
            rtt = 5_000L,
            clientReceived = 2_000_000L
        )
        val collected = manager.onServerTime(measurement)
        assertFalse("After restart and burst completion, should feed directly to filter", collected)

        manager.stop()
    }

    @Test
    fun stopMidBurst_burstInProgressCleared() = runTest {
        manager.start(this)
        advanceTimeBy(10) // Mid-burst

        // Confirm burst is in progress
        val m1 = TimeMeasurement(offset = 1_000L, rtt = 5_000L, clientReceived = 1_000_000L)
        assertTrue("Should be mid-burst", manager.onServerTime(m1))

        // Stop mid-burst - burstInProgress must be cleared
        manager.stop()

        // After stop, onServerTime should not collect for burst
        val m2 = TimeMeasurement(offset = 2_000L, rtt = 5_000L, clientReceived = 2_000_000L)
        val collected = manager.onServerTime(m2)
        assertFalse("After stop mid-burst, should not collect for burst", collected)
    }

    @Test
    fun onServerTime_whenNotRunning_returnsFalse() {
        // Manager never started - onServerTime should reject
        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 1_000_000L
        )
        val result = manager.onServerTime(measurement)
        assertFalse("onServerTime should return false when not running", result)
        assertEquals("Should not feed to filter when not running", 0, timeFilter.measurementCountValue)
    }

    @Test
    fun onServerTime_afterStop_returnsFalse() = runTest {
        manager.start(this)
        advanceTimeBy(700) // Complete the burst
        manager.stop()

        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 3_000_000L
        )
        val result = manager.onServerTime(measurement)
        assertFalse("onServerTime should return false after stop", result)
    }
}
