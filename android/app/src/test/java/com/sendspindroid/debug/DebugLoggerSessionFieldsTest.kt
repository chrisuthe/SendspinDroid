package com.sendspindroid.debug

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Tests for L-16: DebugLogger session state fields must be @Volatile.
 *
 * These fields are written by startSession()/endSession() on the main handler thread
 * and read by buildShareMessage()/getSessionDurationString() which can run from
 * different threads (e.g., share intent creation from UI thread, logStats from
 * debug logging handler).
 *
 * Without @Volatile, cross-thread reads could see stale cached values.
 */
class DebugLoggerSessionFieldsTest {

    private val volatileFieldNames = listOf("serverName", "serverAddress", "sessionStartTimeMs")

    @Test
    fun `serverName field is volatile`() {
        assertFieldIsVolatile("serverName")
    }

    @Test
    fun `serverAddress field is volatile`() {
        assertFieldIsVolatile("serverAddress")
    }

    @Test
    fun `sessionStartTimeMs field is volatile`() {
        assertFieldIsVolatile("sessionStartTimeMs")
    }

    @Test
    fun `all session tracking fields are volatile`() {
        val clazz = DebugLogger::class.java
        for (name in volatileFieldNames) {
            val field = clazz.getDeclaredField(name)
            assertTrue(
                "Field '$name' must be volatile for cross-thread visibility",
                Modifier.isVolatile(field.modifiers)
            )
        }
    }

    @Test
    fun `startSession updates all session fields`() {
        // Verify startSession writes to all three fields.
        // FileLogger calls will be no-ops since init() was never called and isEnabled is false.
        DebugLogger.startSession("TestServer", "192.168.1.100:8080")

        // Use reflection to read private fields
        val clazz = DebugLogger::class.java

        val nameField = clazz.getDeclaredField("serverName").apply { isAccessible = true }
        val addrField = clazz.getDeclaredField("serverAddress").apply { isAccessible = true }
        val timeField = clazz.getDeclaredField("sessionStartTimeMs").apply { isAccessible = true }

        val name = nameField.get(DebugLogger) as String
        val addr = addrField.get(DebugLogger) as String
        val time = timeField.get(DebugLogger) as Long

        assertTrue("serverName should be set", name == "TestServer")
        assertTrue("serverAddress should be set", addr == "192.168.1.100:8080")
        assertTrue("sessionStartTimeMs should be non-zero", time > 0)
    }

    private fun assertFieldIsVolatile(fieldName: String) {
        val field: Field = DebugLogger::class.java.getDeclaredField(fieldName)
        assertTrue(
            "Field '$fieldName' must be @Volatile for cross-thread visibility (L-16)",
            Modifier.isVolatile(field.modifiers)
        )
    }
}
