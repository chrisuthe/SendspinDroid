package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class SendSpinEndpointTest {
    @Test
    fun `Local carries address and path`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Local("10.0.1.5:8927", "/sendspin")
        assertEquals("10.0.1.5:8927", (e as SendSpinEndpoint.Local).address)
        assertEquals("/sendspin", e.path)
    }

    @Test
    fun `Local default path is the SendSpin endpoint constant`() {
        val e = SendSpinEndpoint.Local("10.0.1.5:8927")
        assertEquals(SendSpinProtocol.ENDPOINT_PATH, e.path)
    }

    @Test
    fun `Proxy carries url and authToken`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Proxy("https://example.com/sendspin", "tok")
        assertEquals("https://example.com/sendspin", (e as SendSpinEndpoint.Proxy).url)
        assertEquals("tok", e.authToken)
    }

    @Test
    fun `Remote carries remoteId`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Remote("ABCDEFGH012345678901234567")
        assertEquals("ABCDEFGH012345678901234567", (e as SendSpinEndpoint.Remote).remoteId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<SendSpinEndpoint> = listOf(
            SendSpinEndpoint.Local("a", "/p"),
            SendSpinEndpoint.Proxy("u", "t"),
            SendSpinEndpoint.Remote("r"),
        )
        val labels = cases.map {
            when (it) {
                is SendSpinEndpoint.Local -> "local"
                is SendSpinEndpoint.Proxy -> "proxy"
                is SendSpinEndpoint.Remote -> "remote"
            }
        }
        assertEquals(listOf("local", "proxy", "remote"), labels)
    }
}
