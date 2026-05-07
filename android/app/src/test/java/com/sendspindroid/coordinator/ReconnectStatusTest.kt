package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectStatusTest {
    @Test
    fun `Attempting carries server id, attempt counters, and method`() {
        val s: ReconnectStatus = ReconnectStatus.Attempting(
            serverId = "s1",
            attempt = 3,
            maxAttempts = 11,
            method = ConnectionType.LOCAL,
        )
        assertEquals("s1", (s as ReconnectStatus.Attempting).serverId)
        assertEquals(3, s.attempt)
        assertEquals(11, s.maxAttempts)
        assertEquals(ConnectionType.LOCAL, s.method)
    }

    @Test
    fun `Failed and Succeeded carry server id`() {
        val f: ReconnectStatus = ReconnectStatus.Failed("s1", "boom")
        val ok: ReconnectStatus = ReconnectStatus.Succeeded("s1")
        assertEquals("s1", (f as ReconnectStatus.Failed).serverId)
        assertEquals("boom", f.error)
        assertEquals("s1", (ok as ReconnectStatus.Succeeded).serverId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<ReconnectStatus> = listOf(
            ReconnectStatus.Idle,
            ReconnectStatus.Attempting("s", 1, 1, null),
            ReconnectStatus.Succeeded("s"),
            ReconnectStatus.Failed("s", "e"),
        )
        val labels = cases.map {
            when (it) {
                ReconnectStatus.Idle -> "idle"
                is ReconnectStatus.Attempting -> "attempting"
                is ReconnectStatus.Succeeded -> "succeeded"
                is ReconnectStatus.Failed -> "failed"
            }
        }
        assertEquals(listOf("idle", "attempting", "succeeded", "failed"), labels)
    }
}
