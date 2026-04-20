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
}
