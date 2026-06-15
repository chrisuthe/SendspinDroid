package com.sendspindroid.musicassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MaEndpointTest {
    @Test
    fun `Local carries address and port`() {
        val e: MaEndpoint = MaEndpoint.Local("10.0.1.5", 8095)
        assertEquals("10.0.1.5", (e as MaEndpoint.Local).address)
        assertEquals(8095, e.port)
    }

    @Test
    fun `Proxy carries baseUrl`() {
        val e: MaEndpoint = MaEndpoint.Proxy("https://example.com")
        assertEquals("https://example.com", (e as MaEndpoint.Proxy).baseUrl)
    }

    @Test
    fun `Remote carries remoteId`() {
        val e: MaEndpoint = MaEndpoint.Remote("ABCDEFGH012345678901234567")
        assertEquals("ABCDEFGH012345678901234567", (e as MaEndpoint.Remote).remoteId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<MaEndpoint> = listOf(
            MaEndpoint.Local("a", 1),
            MaEndpoint.Proxy("u"),
            MaEndpoint.Remote("r"),
        )
        val labels = cases.map {
            when (it) {
                is MaEndpoint.Local -> "local"
                is MaEndpoint.Proxy -> "proxy"
                is MaEndpoint.Remote -> "remote"
            }
        }
        assertEquals(listOf("local", "proxy", "remote"), labels)
    }
}
