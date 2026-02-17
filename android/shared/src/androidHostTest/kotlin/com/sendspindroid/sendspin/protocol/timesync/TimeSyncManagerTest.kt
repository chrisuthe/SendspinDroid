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
    fun onServerTime_outsideBurst_feedsToFilter() {
        val measurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 5_000L,
            clientReceived = 1_000_000L
        )

        val collected = manager.onServerTime(measurement)
        assertFalse("Should not report as collected (no burst)", collected)
        assertEquals(1, timeFilter.measurementCountValue)
    }

    @Test
    fun onServerTime_outsideBurst_staleRtt_isIgnored() {
        val staleMeasurement = TimeMeasurement(
            offset = 10_000L,
            rtt = 15_000_000L,
            clientReceived = 1_000_000L
        )

        val collected = manager.onServerTime(staleMeasurement)
        assertFalse(collected)
        assertEquals(0, timeFilter.measurementCountValue)
    }

    @Test
    fun onServerTime_outsideBurst_validRtt_acceptedByFilter() {
        val measurement = TimeMeasurement(
            offset = 50_000L,
            rtt = 20_000L,
            clientReceived = 1_000_000L
        )

        manager.onServerTime(measurement)
        assertEquals(1, timeFilter.measurementCountValue)
        assertEquals(50_000L, timeFilter.offsetMicros)
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
}
