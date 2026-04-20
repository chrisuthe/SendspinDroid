package com.sendspindroid.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketUrlBuilderTest {

    @Test
    fun ipv4_with_port() {
        assertEquals(
            "ws://192.168.1.100:8927/sendspin",
            WebSocketUrlBuilder.build("192.168.1.100:8927", "/sendspin")
        )
    }

    @Test
    fun ipv4_without_port() {
        assertEquals(
            "ws://192.168.1.100/sendspin",
            WebSocketUrlBuilder.build("192.168.1.100", "/sendspin")
        )
    }

    @Test
    fun hostname_with_port() {
        assertEquals(
            "ws://host.example.com:8927/sendspin",
            WebSocketUrlBuilder.build("host.example.com:8927", "/sendspin")
        )
    }

    @Test
    fun hostname_without_port() {
        assertEquals(
            "ws://host.example.com/sendspin",
            WebSocketUrlBuilder.build("host.example.com", "/sendspin")
        )
    }

    @Test
    fun ipv6_literal_bare_is_wrapped() {
        assertEquals(
            "ws://[2001:db8::1]/sendspin",
            WebSocketUrlBuilder.build("2001:db8::1", "/sendspin")
        )
    }

    @Test
    fun ipv6_literal_bracketed_with_port_is_preserved() {
        assertEquals(
            "ws://[2001:db8::1]:8927/sendspin",
            WebSocketUrlBuilder.build("[2001:db8::1]:8927", "/sendspin")
        )
    }

    @Test
    fun ipv6_literal_bracketed_without_port() {
        assertEquals(
            "ws://[2001:db8::1]/sendspin",
            WebSocketUrlBuilder.build("[2001:db8::1]", "/sendspin")
        )
    }

    @Test
    fun loopback_ipv6() {
        assertEquals(
            "ws://[::1]/sendspin",
            WebSocketUrlBuilder.build("::1", "/sendspin")
        )
    }

    @Test
    fun path_without_leading_slash_is_normalized() {
        assertEquals(
            "ws://host/sendspin",
            WebSocketUrlBuilder.build("host", "sendspin")
        )
    }

    @Test
    fun empty_path_produces_no_trailing_slash() {
        assertEquals(
            "ws://host",
            WebSocketUrlBuilder.build("host", "")
        )
    }

    @Test
    fun wss_scheme_is_used_verbatim() {
        assertEquals(
            "wss://host.example.com/path",
            WebSocketUrlBuilder.build("host.example.com", "/path", scheme = "wss")
        )
    }

    @Test
    fun buildFromHostPort_ipv4() {
        assertEquals(
            "ws://192.168.1.1:8927/ws",
            WebSocketUrlBuilder.buildFromHostPort("192.168.1.1", 8927, "/ws")
        )
    }

    @Test
    fun buildFromHostPort_ipv6_wraps() {
        assertEquals(
            "ws://[2001:db8::1]:8927/ws",
            WebSocketUrlBuilder.buildFromHostPort("2001:db8::1", 8927, "/ws")
        )
    }

    @Test
    fun buildFromHostPort_bracketed_ipv6_not_double_wrapped() {
        assertEquals(
            "ws://[2001:db8::1]:8927/ws",
            WebSocketUrlBuilder.buildFromHostPort("[2001:db8::1]", 8927, "/ws")
        )
    }

    @Test
    fun buildFromHostPort_hostname() {
        assertEquals(
            "ws://host.example.com:8927/ws",
            WebSocketUrlBuilder.buildFromHostPort("host.example.com", 8927, "/ws")
        )
    }

    @Test
    fun buildFromHostPort_wss_scheme() {
        assertEquals(
            "wss://host.example.com:8927/ws",
            WebSocketUrlBuilder.buildFromHostPort("host.example.com", 8927, "/ws", scheme = "wss")
        )
    }

    @Test
    fun empty_address_produces_schemeless_authority() {
        // Documents current behavior: no input validation. Callers must ensure
        // the address is non-empty. The wizard validates this upstream.
        assertEquals("ws:///path", WebSocketUrlBuilder.build("", "/path"))
    }

    // --- ensureDefaultPort ---

    @Test
    fun ensureDefaultPort_bare_hostname_appends_default() {
        assertEquals("host.example.com:8927", WebSocketUrlBuilder.ensureDefaultPort("host.example.com", 8927))
    }

    @Test
    fun ensureDefaultPort_ipv4_appends_default() {
        assertEquals("192.168.1.1:8927", WebSocketUrlBuilder.ensureDefaultPort("192.168.1.1", 8927))
    }

    @Test
    fun ensureDefaultPort_hostname_with_port_unchanged() {
        assertEquals("host.example.com:8080", WebSocketUrlBuilder.ensureDefaultPort("host.example.com:8080", 8927))
    }

    @Test
    fun ensureDefaultPort_bare_ipv6_wraps_and_appends() {
        assertEquals("[2001:db8::1]:8927", WebSocketUrlBuilder.ensureDefaultPort("2001:db8::1", 8927))
    }

    @Test
    fun ensureDefaultPort_bracketed_ipv6_without_port_appends() {
        assertEquals("[2001:db8::1]:8927", WebSocketUrlBuilder.ensureDefaultPort("[2001:db8::1]", 8927))
    }

    @Test
    fun ensureDefaultPort_bracketed_ipv6_with_port_unchanged() {
        assertEquals("[2001:db8::1]:8080", WebSocketUrlBuilder.ensureDefaultPort("[2001:db8::1]:8080", 8927))
    }

    // --- extractHost ---

    @Test
    fun extractHost_bare_hostname() {
        assertEquals("host.example.com", WebSocketUrlBuilder.extractHost("host.example.com"))
    }

    @Test
    fun extractHost_hostname_with_port() {
        assertEquals("host.example.com", WebSocketUrlBuilder.extractHost("host.example.com:8080"))
    }

    @Test
    fun extractHost_ipv4() {
        assertEquals("192.168.1.1", WebSocketUrlBuilder.extractHost("192.168.1.1"))
    }

    @Test
    fun extractHost_ipv4_with_port() {
        assertEquals("192.168.1.1", WebSocketUrlBuilder.extractHost("192.168.1.1:8927"))
    }

    @Test
    fun extractHost_bare_ipv6() {
        assertEquals("2001:db8::1", WebSocketUrlBuilder.extractHost("2001:db8::1"))
    }

    @Test
    fun extractHost_bracketed_ipv6() {
        assertEquals("2001:db8::1", WebSocketUrlBuilder.extractHost("[2001:db8::1]"))
    }

    @Test
    fun extractHost_bracketed_ipv6_with_port() {
        assertEquals("2001:db8::1", WebSocketUrlBuilder.extractHost("[2001:db8::1]:8927"))
    }
}
