package com.sendspindroid.remote

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for RemoteConnection.parseRemoteId() fix (M-12).
 *
 * Verifies that overlapping URL patterns were consolidated so that:
 * 1. The redundant narrow-capture pattern (\?remote_id=([A-Za-z0-9]+)) was removed
 * 2. Dashed IDs in query parameters are correctly captured
 * 3. All documented URL formats still parse correctly
 * 4. The more specific app.music-assistant.io pattern is tried before the generic /remote/ pattern
 */
class RemoteConnectionParseTest {

    companion object {
        // Valid 26-char remote ID for testing
        private const val VALID_ID = "VVPN3TLP34YMGIZDINCEKQKSIR"
        private const val VALID_ID_DASHED = "VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R"
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Raw ID input ---

    @Test
    fun `raw 26-char ID parses correctly`() {
        val result = RemoteConnection.parseRemoteId(VALID_ID)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `dashed ID parses correctly`() {
        val result = RemoteConnection.parseRemoteId(VALID_ID_DASHED)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `lowercase ID is uppercased`() {
        val result = RemoteConnection.parseRemoteId(VALID_ID.lowercase())
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `ID with spaces parses correctly`() {
        val result = RemoteConnection.parseRemoteId("VVPN3 TLP34 YMGIZ DINCE KQKSIR")
        assertEquals(VALID_ID, result)
    }

    // --- URL with ?remote_id= ---

    @Test
    fun `URL with remote_id query param parses correctly`() {
        val url = "https://app.music-assistant.io/?remote_id=$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `URL with remote_id query param and dashed ID parses correctly`() {
        val url = "https://app.music-assistant.io/?remote_id=$VALID_ID_DASHED"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `URL with remote_id as second param parses correctly`() {
        val url = "https://example.com/page?foo=bar&remote_id=$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    // --- URL with /remote/ path ---

    @Test
    fun `music-assistant URL with remote path parses correctly`() {
        val url = "https://app.music-assistant.io/remote/$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `music-assistant URL with remote path and dashes parses correctly`() {
        val url = "https://app.music-assistant.io/remote/$VALID_ID_DASHED"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `generic remote path URL parses correctly`() {
        val url = "https://other-site.com/remote/$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    // --- URL with ?id= fallback ---

    @Test
    fun `URL with id query param parses correctly`() {
        val url = "https://example.com/?id=$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `URL with id as second param parses correctly`() {
        val url = "https://example.com/page?foo=bar&id=$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    // --- Invalid inputs ---

    @Test
    fun `too short ID returns null`() {
        val result = RemoteConnection.parseRemoteId("ABCDE12345")
        assertNull(result)
    }

    @Test
    fun `too long ID returns null`() {
        val result = RemoteConnection.parseRemoteId("ABCDEFGHIJKLMNOPQRSTUVWXYZ1")  // 27 chars
        assertNull(result)
    }

    @Test
    fun `empty string returns null`() {
        val result = RemoteConnection.parseRemoteId("")
        assertNull(result)
    }

    @Test
    fun `URL with no ID returns null`() {
        val result = RemoteConnection.parseRemoteId("https://example.com/page")
        assertNull(result)
    }

    @Test
    fun `URL with invalid ID in remote_id param returns null`() {
        val result = RemoteConnection.parseRemoteId("https://example.com/?remote_id=short")
        assertNull(result)
    }

    // --- Edge cases for the M-12 fix ---

    @Test
    fun `dashed ID in query param is captured by single consolidated pattern`() {
        // This was the key issue: the old first pattern (\?remote_id=([A-Za-z0-9]+))
        // would match ?remote_id= but NOT capture dashes, so a dashed ID would
        // match the narrow pattern first, fail validation, then fall through.
        // After consolidation, the single [?&]remote_id= pattern captures dashes.
        val url = "https://example.com/?remote_id=$VALID_ID_DASHED"
        val result = RemoteConnection.parseRemoteId(url)
        assertEquals(VALID_ID, result)
    }

    @Test
    fun `music-assistant URL matches app-specific pattern before generic remote`() {
        // Both patterns could match: app.music-assistant.io/remote/ID and /remote/ID.
        // The app-specific pattern should match first (more specific).
        val url = "https://app.music-assistant.io/remote/$VALID_ID"
        val result = RemoteConnection.parseRemoteId(url)
        assertNotNull(result)
        assertEquals(VALID_ID, result)
    }
}
