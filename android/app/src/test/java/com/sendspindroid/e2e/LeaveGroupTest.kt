package com.sendspindroid.e2e

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "client/leave": leave the group, stay connected and available. */
class LeaveGroupTest : E2ETestBase() {

    @Test
    fun `leaving sends client leave with an empty payload`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeServer.sendServerHello()
        fakeServer.sendServerActivate(activities = listOf("playback"), activeRoles = listOf("player@v1"))

        assertTrue(client.leaveGroup())

        assertTrue(fakeTransport.hasSentMessageContaining("client/leave"))
        val sent = fakeTransport.sentTextMessages.first { "client/leave" in it }
        assertTrue(sent, "\"payload\":{}" in sent)
    }

    @Test
    fun `nothing to leave before the activation`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeServer.sendServerHello()

        assertFalse(client.leaveGroup())
    }
}
