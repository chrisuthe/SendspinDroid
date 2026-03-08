package com.sendspindroid.remote

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [RemoteConnection.isValidRemoteId] validation logic.
 *
 * Verifies rejection of lowercase, special characters, wrong length,
 * and empty inputs.
 */
class RemoteConnectionValidationTest {

    companion object {
        private const val VALID_ID = "VVPN3TLP34YMGIZDINCEKQKSIR" // 26 uppercase alphanumeric
    }

    @Test
    fun `valid 26-char uppercase alphanumeric ID is accepted`() {
        assertTrue(RemoteConnection.isValidRemoteId(VALID_ID))
    }

    @Test
    fun `all digits 26-char ID is accepted`() {
        assertTrue(RemoteConnection.isValidRemoteId("12345678901234567890123456"))
    }

    @Test
    fun `lowercase letters are rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("vvpn3tlp34ymgizdincekqksir"))
    }

    @Test
    fun `mixed case is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3TLP34YMGIZDINCEKQKSir"))
    }

    @Test
    fun `special characters are rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3-TLP34-YMGIZ-DINCE-KQ"))
    }

    @Test
    fun `spaces are rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3 TLP34 YMGIZDINCEKQKSI"))
    }

    @Test
    fun `too short ID is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("ABCDE12345"))
    }

    @Test
    fun `25-char ID is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("ABCDEFGHIJKLMNOPQRSTUVWXY"))
    }

    @Test
    fun `27-char ID is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("ABCDEFGHIJKLMNOPQRSTUVWXYZ1"))
    }

    @Test
    fun `empty string is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId(""))
    }

    @Test
    fun `underscore is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3TLP34YMGIZDINC_KQKSIR"))
    }

    @Test
    fun `dot is rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3TLP34YMGIZDINC.KQKSIR"))
    }

    @Test
    fun `unicode letters are rejected`() {
        // 26 chars but contains non-ASCII
        assertFalse(RemoteConnection.isValidRemoteId("ABCDEFGHIJKLMNOPQRSTUVWXY\u00C9"))
    }
}
