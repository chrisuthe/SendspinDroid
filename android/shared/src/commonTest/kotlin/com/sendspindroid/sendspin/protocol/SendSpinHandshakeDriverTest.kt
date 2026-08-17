package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseHandshake
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.protocol.message.InitMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Drives the cleartext handshake against a transcript produced by
 * `noiseprotocol` acting as the server. See [WireTestVectors].
 *
 * The load-bearing assertion is [driverReproducesTheReferenceNoiseMessage2]: it
 * only passes if the prologue was built from the exact bytes of `client/init`
 * and `server/init`. The recorded `server/init` orders its fields differently
 * from the client's and carries an unknown key, so any implementation that
 * parses and re-encodes it produces a different prologue and fails.
 */
class SendSpinHandshakeDriverTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.hex() = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private val psk = Psk(hex(WireTestVectors.psk), PskCategory.LONG_TERM, WireTestVectors.serverId)
    private val candidates = PskCandidateSet.of(listOf(psk)).getOrThrow()

    private class Recorder {
        val sent = mutableListOf<String>()
        val failures = mutableListOf<Pair<NoiseHandshakeException.Cause, String>>()
        var ready: SendSpinHandshakeDriver.Event.TransportReady? = null

        fun handle(e: SendSpinHandshakeDriver.Event) {
            when (e) {
                is SendSpinHandshakeDriver.Event.SendCleartext -> sent += e.text
                is SendSpinHandshakeDriver.Event.Fail -> failures += e.reason to e.detail
                is SendSpinHandshakeDriver.Event.TransportReady -> ready = e
            }
        }
    }

    /**
     * A driver whose ephemeral is pinned to the transcript's, so message 2 is
     * byte-reproducible. Mirrors the production path exactly apart from that.
     */
    private fun driverWith(recorder: Recorder): SendSpinHandshakeDriver =
        SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = candidates,
            onEvent = recorder::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )

    private fun runHandshake(recorder: Recorder): SendSpinHandshakeDriver {
        val driver = driverWith(recorder)
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        return driver
    }

    @Test
    fun builderReproducesTheReferenceClientInitByteForByte() {
        // If this fails, every prologue below is built from different bytes than
        // the transcript assumed - regenerate the vectors.
        assertEquals(
            WireTestVectors.clientInitFrame,
            InitMessages.buildClientInit(WireTestVectors.clientId, WireTestVectors.suiteWireName),
        )
    }

    @Test
    fun startEmitsClientInitFirst() {
        val r = Recorder()
        driverWith(r).start()
        assertEquals(1, r.sent.size)
        assertEquals(WireTestVectors.clientInitFrame, r.sent[0])
    }

    @Test
    fun driverReproducesTheReferenceNoiseMessage2() {
        // The prologue test. Passing means client/init and server/init were both
        // hashed as raw bytes.
        val r = Recorder()
        runHandshake(r)
        assertTrue(r.failures.isEmpty(), "unexpected failure: ${r.failures}")
        assertEquals(2, r.sent.size)
        val expected = InitMessages.buildNoiseHandshake(WireTestVectors.noiseMessage2B64u)
        assertEquals(expected, r.sent[1])
    }

    @Test
    fun handshakeHashAgreesWithTheReference() {
        val r = Recorder()
        runHandshake(r)
        val ready = assertNotNull(r.ready)
        assertEquals(WireTestVectors.handshakeHash, ready.transport.handshakeHash.hex())
        assertEquals(WireTestVectors.serverId, ready.serverInit.serverId)
        assertEquals(WireTestVectors.pskId, ready.matchedPsk.pskId)
    }

    @Test
    fun reEncodingServerInitBreaksTheHandshake() {
        // Proves the transcript actually detects the mistake it was built to
        // detect: feed a semantically identical server/init whose bytes differ
        // (field order normalised, unknown key dropped) and message 2 diverges.
        val reEncoded = InitMessages.let {
            """{"type":"server/init","payload":{"server_id":"${WireTestVectors.serverId}","version":1}}"""
        }
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onCleartextFrame(reEncoded.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        // message 1 no longer authenticates under the wrong prologue.
        assertTrue(r.failures.isNotEmpty(), "a re-encoded server/init must fail")
        assertEquals(NoiseHandshakeException.Cause.AeadFailure, r.failures[0].first)
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
    }

    @Test
    fun transportCarriesApplicationMessagesBothWays() = runTest {
        val r = Recorder()
        runHandshake(r)
        val transport = assertNotNull(r.ready).transport
        val codec = NoiseWireCodec(transport)

        val inbound = codec.decode(hex(WireTestVectors.appFrameServerToClient))
        val json = assertTrue(inbound is NoiseWireCodec.Decoded.Json).let { inbound as NoiseWireCodec.Decoded.Json }
        // The recorded plaintext includes the leading type byte; the codec strips it.
        assertEquals(
            WireTestVectors.appFrameServerToClientPlaintext.substring(1),
            json.text,
        )

        val outbound = codec.encodeJson(
            WireTestVectors.appFrameClientToServerPlaintext.substring(1)
        )
        assertEquals(1, outbound.size)
        assertEquals(WireTestVectors.appFrameClientToServer, outbound[0].hex())
    }

    @Test
    fun anUnknownPskIdFailsAsALookupMiss() {
        val r = Recorder()
        val driver = SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            // Sentinel only: the transcript's PSK is not a candidate.
            candidates = PskCandidateSet.sentinelOnly(),
            onEvent = r::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        assertEquals(NoiseHandshakeException.Cause.PskLookupMiss, r.failures.single().first)
        assertNull(r.ready)
    }

    @Test
    fun aRecordBoundToAnotherServerIsRejected() {
        val wrongBinding = Psk(hex(WireTestVectors.psk), PskCategory.LONG_TERM, "some-other-server")
        val r = Recorder()
        val driver = SendSpinHandshakeDriver(
            identity = ClientIdentity(hex(WireTestVectors.clientStaticPrivate)),
            candidates = PskCandidateSet.of(listOf(wrongBinding)).getOrThrow(),
            onEvent = r::handle,
            ephemeralOverrideForTest = { hex(WireTestVectors.clientEphemeralPrivate) },
        )
        driver.start()
        driver.onCleartextFrame(WireTestVectors.serverInitFrame.encodeToByteArray())
        driver.onCleartextFrame(WireTestVectors.noiseHandshake1Frame.encodeToByteArray())
        assertEquals(NoiseHandshakeException.Cause.PskLookupMiss, r.failures.single().first)
    }

    @Test
    fun aTextFrameAfterTransportModeIsRejected() {
        // "all messages are sent as WebSocket binary frames" once the handshake
        // completes; a text frame afterwards is a downgrade attempt or a bug.
        val r = Recorder()
        val driver = runHandshake(r)
        assertEquals(SendSpinHandshakeDriver.Phase.Transport, driver.phase)
        driver.onCleartextFrame("""{"type":"server/hello"}""".encodeToByteArray())
        assertEquals(NoiseHandshakeException.Cause.MalformedMessage, r.failures.single().first)
    }

    @Test
    fun aBinaryFrameDuringTheHandshakeIsRejected() {
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onBinaryFrameBeforeTransport()
        assertEquals(NoiseHandshakeException.Cause.MalformedMessage, r.failures.single().first)
        assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
    }

    @Test
    fun outOfOrderAndMalformedFramesFailWithoutSendingAnything() {
        val cases = listOf(
            "not json at all",
            """{"payload":{}}""",
            """{"type":"server/hello","payload":{}}""",
            """{"type":"server/init","payload":{"version":2,"server_id":"${WireTestVectors.serverId}"}}""",
            """{"type":"server/init","payload":{"version":1,"server_id":"too-short"}}""",
        )
        for (frame in cases) {
            val r = Recorder()
            val driver = driverWith(r)
            driver.start()
            driver.onCleartextFrame(frame.encodeToByteArray())
            assertTrue(r.failures.isNotEmpty(), "should have failed: $frame")
            // Exactly one frame was ever sent: our own client/init.
            assertEquals(1, r.sent.size, "must not reply to a bad frame: $frame")
            assertEquals(SendSpinHandshakeDriver.Phase.Failed, driver.phase)
        }
    }

    @Test
    fun timeoutFailsBeforeTransportAndIsIgnoredAfter() {
        val r = Recorder()
        val driver = driverWith(r)
        driver.start()
        driver.onTimeout()
        assertEquals(NoiseHandshakeException.Cause.Timeout, r.failures.single().first)

        val r2 = Recorder()
        val done = runHandshake(r2)
        assertEquals(SendSpinHandshakeDriver.Phase.Transport, done.phase)
        done.onTimeout()
        assertTrue(r2.failures.isEmpty(), "a completed handshake must ignore the watchdog")
    }
}
