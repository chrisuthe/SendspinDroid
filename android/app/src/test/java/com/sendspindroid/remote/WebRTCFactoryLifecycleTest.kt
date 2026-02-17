package com.sendspindroid.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Structural tests for WebRTCTransport factory lifecycle (H-23).
 *
 * The actual WebRTC native library is not available in unit tests, so these
 * tests verify the companion object's API surface and field declarations
 * using reflection. Integration testing of initializeFactory/releaseFactory
 * must be done on-device.
 *
 * What H-23 fixed:
 * - Removed EglBase (GPU resource leak in audio-only app)
 * - Added releaseFactory() for proper cleanup
 */
class WebRTCFactoryLifecycleTest {

    private val companionClass = WebRTCTransport.Companion::class.java

    @Test
    fun releaseFactory_methodExists() {
        val method = companionClass.getMethod("releaseFactory")
        assertTrue("releaseFactory() should be public", Modifier.isPublic(method.modifiers))
    }

    @Test
    fun initializeFactory_methodExists() {
        // Verify the existing method is still present
        val method = companionClass.getMethod("initializeFactory", android.content.Context::class.java)
        assertTrue("initializeFactory() should be public", Modifier.isPublic(method.modifiers))
    }

    @Test
    fun eglBase_fieldDoesNotExist() {
        // After H-23 fix, eglBase should no longer exist as a field in the companion
        val companionObjectClass = Class.forName("com.sendspindroid.remote.WebRTCTransport\$Companion")
        // The actual backing fields are on the outer class (Kotlin object singletons)
        val outerClass = WebRTCTransport::class.java
        val fieldNames = outerClass.declaredFields.map { it.name }
        assertFalse(
            "eglBase field should have been removed (H-23)",
            fieldNames.contains("eglBase")
        )
    }

    @Test
    fun peerConnectionFactory_fieldExists() {
        // The peerConnectionFactory field should still exist
        val outerClass = WebRTCTransport::class.java
        val fieldNames = outerClass.declaredFields.map { it.name }
        assertTrue(
            "peerConnectionFactory field should exist",
            fieldNames.contains("peerConnectionFactory")
        )
    }

    @Test
    fun factoryInitialized_fieldExists() {
        // The factoryInitialized flag should still exist
        val outerClass = WebRTCTransport::class.java
        val fieldNames = outerClass.declaredFields.map { it.name }
        assertTrue(
            "factoryInitialized field should exist",
            fieldNames.contains("factoryInitialized")
        )
    }
}
