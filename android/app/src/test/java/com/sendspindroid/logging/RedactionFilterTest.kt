package com.sendspindroid.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RedactionFilter] — the scrubber applied to anything leaving the device
 * (log export, bug-report bundles, telemetry). Verifies it removes IPs/hosts/URLs/
 * tokens and caller-supplied sensitive terms while preserving normal log content
 * (and, critically, not mangling timestamps).
 */
class RedactionFilterTest {

    private val filter = RedactionFilter()

    @Test
    fun `redacts IPv4 address with port`() {
        val out = filter.redact("connecting to 192.168.1.100:8927 now")
        assertFalse(out.contains("192.168.1.100"))
        assertTrue(out.contains("<ip>"))
    }

    @Test
    fun `redacts ws and wss and http URLs keeping the scheme`() {
        assertEquals("ws://<redacted>", filter.redact("ws://10.0.2.8:8927/ws"))
        assertEquals("https://<redacted>", filter.redact("https://music.home.example.com/imageproxy/abc?size=512"))
        assertEquals("ma-proxy://<redacted>", filter.redact("ma-proxy:///imageproxy/deadbeef"))
    }

    @Test
    fun `redacts bracketed and compressed IPv6`() {
        assertFalse(filter.redact("peer [2001:db8::1]:8927").contains("2001:db8"))
        assertFalse(filter.redact("loopback ::1 reached").contains("::1"))
    }

    @Test
    fun `does not mangle a logcat timestamp`() {
        val line = "06-15 17:50:41.459 20413 D SendSpin: time sync ok"
        assertEquals(line, filter.redact(line))
    }

    @Test
    fun `redacts JWT and labeled tokens`() {
        assertTrue(filter.redact("token=eyJhbGc.eyJzdWI.c2lnbmF0dXJl").contains("<token>"))
        val labeled = filter.redact("authToken=s3cr3t-value-xyz")
        assertFalse(labeled.contains("s3cr3t-value-xyz"))
        assertTrue(labeled.contains("<token>"))
    }

    @Test
    fun `redacts caller-supplied sensitive terms (server name and host)`() {
        val f = RedactionFilter(listOf("Living Room", "music.home.example.com"))
        val out = f.redact("Session started: Living Room (music.home.example.com:8095)")
        assertFalse(out.contains("Living Room"))
        assertFalse(out.contains("music.home.example.com"))
    }

    @Test
    fun `ignores blank and too-short sensitive terms`() {
        val f = RedactionFilter(listOf("", "  ", "a"))
        // A lone short term must not redact every 'a' in the text.
        assertEquals("a quick audio sample", f.redact("a quick audio sample"))
    }

    @Test
    fun `preserves ordinary log content and app version`() {
        val line = "AudioTrack initialized: 48000Hz, 2ch, 16bit (v2.0.0-Beta12)"
        assertEquals(line, filter.redact(line))
    }
}
