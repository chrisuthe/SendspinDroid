package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.NoiseHandshake
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.protocol.message.InitMessages
import com.sendspindroid.sendspin.protocol.message.ServerInit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives the cleartext half of the connection:
 *
 *     client -> client/init          (text)
 *     server -> server/init          (text)
 *     server -> noise/handshake #1   (text)
 *     client -> noise/handshake #2   (text)
 *     ... transport mode; everything after is a binary Noise ciphertext
 *
 * Its real job is the prologue. `connection.md#prologue` requires hashing "the
 * concatenation of the exact bytes of `client/init` followed by the exact bytes
 * of `server/init`, as transmitted on the wire", and "Both sides MUST hash the
 * raw message bytes exactly as sent and received, not a re-encoding of the
 * parsed message."
 *
 * So this class retains the exact String it sent and the exact bytes it
 * received, and never rebuilds either. `kotlinx.serialization` will not
 * round-trip byte-identically - key order, escaping and number formatting can
 * all differ - so a driver that parsed `server/init` and re-encoded it for the
 * prologue would pass every unit test on the parsed object and fail against
 * every real server, with no error message on either side.
 *
 * Every failure path here closes the socket and sends nothing
 * (`connection.md#failure-handling`).
 */
class SendSpinHandshakeDriver(
    private val identity: ClientIdentity,
    private val candidates: PskCandidateSet,
    private val suite: NoiseCipherSuite = DEFAULT_SUITE,
    private val onEvent: (Event) -> Unit = {},
    /**
     * Test seam: pin the Noise ephemeral so a transcript is reproducible.
     * `internal` so it cannot be reached from app code - a production caller
     * able to fix the ephemeral would silently destroy forward secrecy.
     */
    internal val ephemeralOverrideForTest: (() -> ByteArray)? = null,
) {
    /** What the driver wants the connection layer to do. */
    sealed interface Event {
        /** Put this on the wire as a WebSocket TEXT frame. */
        data class SendCleartext(val text: String) : Event

        /** The handshake completed; use this transport for everything after. */
        data class TransportReady(
            val transport: com.sendspindroid.sendspin.crypto.NoiseTransport,
            val serverInit: ServerInit,
            val matchedPsk: Psk,
        ) : Event

        /** Close the socket. Send nothing - the spec allows no error message. */
        data class Fail(val reason: NoiseHandshakeException.Cause, val detail: String) : Event
    }

    enum class Phase { New, AwaitingServerInit, AwaitingNoiseMessage1, Transport, Failed }

    var phase: Phase = Phase.New
        private set

    /** Retained verbatim; the first half of the prologue. */
    private var clientInitSent: String? = null

    /** Retained verbatim; the second half of the prologue. */
    private var serverInitRaw: ByteArray? = null

    private var handshake: NoiseHandshake? = null
    private var serverInit: ServerInit? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Call once the socket is open. Emits the `client/init` frame to send. */
    fun start() {
        if (phase != Phase.New) return fail(
            NoiseHandshakeException.Cause.WrongPhase, "start() called in $phase"
        )
        val text = InitMessages.buildClientInit(identity.clientId, suite.wireName)
        clientInitSent = text
        phase = Phase.AwaitingServerInit
        onEvent(Event.SendCleartext(text))
    }

    /**
     * Feed a cleartext text frame.
     *
     * @param raw the frame's exact UTF-8 bytes as received. Do NOT pass
     *   `text.encodeToByteArray()` - that is a re-encoding, and the whole point
     *   of this parameter is to avoid one.
     */
    fun onCleartextFrame(raw: ByteArray) {
        when (phase) {
            Phase.AwaitingServerInit -> handleServerInit(raw)
            Phase.AwaitingNoiseMessage1 -> handleNoiseMessage1(raw)
            Phase.Transport -> fail(
                NoiseHandshakeException.Cause.MalformedMessage,
                "text frame received after transport mode began; every message " +
                    "after the handshake must be a binary Noise ciphertext",
            )
            else -> fail(
                NoiseHandshakeException.Cause.WrongPhase,
                "cleartext frame in phase $phase",
            )
        }
    }

    /** A binary frame before transport mode is a protocol violation. */
    fun onBinaryFrameBeforeTransport() {
        fail(
            NoiseHandshakeException.Cause.MalformedMessage,
            "binary frame received during the cleartext handshake",
        )
    }

    /** The 30-second watchdog from `connection.md#failure-handling` expired. */
    fun onTimeout() {
        if (phase == Phase.Transport || phase == Phase.Failed) return
        fail(NoiseHandshakeException.Cause.Timeout, "handshake did not complete in time")
    }

    private fun handleServerInit(raw: ByteArray) {
        val envelope = parseEnvelope(raw) ?: return
        if (envelope.first != SendSpinProtocol.MessageType.SERVER_INIT) {
            // A server that predates mandatory encryption answers client/init
            // with a legacy server/hello rather than server/init. That is the
            // one failure here a user can do something about, so it gets its
            // own cause instead of being folded into MalformedMessage.
            return fail(
                NoiseHandshakeException.Cause.ServerLacksEncryption,
                "expected server/init, got ${envelope.first}",
            )
        }
        val parsed = InitMessages.parseServerInit(envelope.second) ?: return fail(
            NoiseHandshakeException.Cause.MalformedMessage,
            "server/init missing or has an unusable server_id/version",
        )
        // Retain the bytes exactly as received.
        serverInitRaw = raw
        serverInit = parsed

        val clientInit = clientInitSent ?: return fail(
            NoiseHandshakeException.Cause.WrongPhase, "no client/init retained"
        )
        // The prologue: our client/init exactly as sent, then their server/init
        // exactly as received. Neither is re-encoded.
        val prologue = clientInit.encodeToByteArray() + raw
        val override = ephemeralOverrideForTest
        handshake = if (override == null) {
            NoiseHandshake.responder(
                staticPrivateKey = identity.privateKeyBytes(),
                remoteStaticPublicKey = parsed.serverStaticKey,
                suite = suite,
                prologue = prologue,
            )
        } else {
            NoiseHandshake(
                suite = suite,
                staticPrivateKey = identity.privateKeyBytes(),
                remoteStaticPublicKey = parsed.serverStaticKey,
                prologue = prologue,
                generateEphemeral = override,
            )
        }
        phase = Phase.AwaitingNoiseMessage1
    }

    private fun handleNoiseMessage1(raw: ByteArray) {
        val envelope = parseEnvelope(raw) ?: return
        if (envelope.first != SendSpinProtocol.MessageType.NOISE_HANDSHAKE) {
            return fail(
                NoiseHandshakeException.Cause.MalformedMessage,
                "expected noise/handshake, got ${envelope.first}",
            )
        }
        val noiseBytes = InitMessages.parseNoiseHandshake(envelope.second) ?: return fail(
            NoiseHandshakeException.Cause.MalformedMessage,
            "noise/handshake data missing or not base64url",
        )
        val session = handshake ?: return fail(
            NoiseHandshakeException.Cause.WrongPhase, "no handshake in progress"
        )

        val payload = try {
            session.readMessage1(noiseBytes)
        } catch (e: NoiseHandshakeException) {
            return fail(e.reason, e.message ?: "message 1 failed")
        }

        // The payload decrypts WITHOUT a PSK - that is the point of psk2 - and
        // carries the psk_id telling us which one to mix for message 2.
        val pskId = parsePskId(payload) ?: return fail(
            NoiseHandshakeException.Cause.PayloadNotJson,
            "message 1 payload is not {\"psk_id\": ...}",
        )
        val init = serverInit ?: return fail(
            NoiseHandshakeException.Cause.WrongPhase, "no server/init retained"
        )
        // One call, so the lookup and the stored-pubkey check cannot drift apart
        // or run in the wrong order. Both failures close the socket in silence,
        // so this detail string is the only diagnostic that will ever exist -
        // hence spelling out which of the two happened, and the candidate count.
        val matched = when (val selection = candidates.select(pskId, init.serverId)) {
            is PskCandidateSet.Selection.Matched -> selection.candidate

            PskCandidateSet.Selection.NoMatch -> return fail(
                NoiseHandshakeException.Cause.PskLookupMiss,
                "no candidate PSK matches psk_id $pskId " +
                    "(${candidates.all.size} candidates offered)",
            )

            is PskCandidateSet.Selection.ServerIdMismatch -> return fail(
                // Usually a server that rotated its static keypair rather than
                // an attack: "A server that rotates its static keypair ...
                // appears to clients as a different server."
                NoiseHandshakeException.Cause.PskLookupMiss,
                "psk_id $pskId is a record for server ${selection.expected}, " +
                    "but server/init announced ${selection.actual}",
            )
        }

        val message2 = try {
            session.writeMessage2(matched.bytes)
        } catch (e: NoiseHandshakeException) {
            return fail(e.reason, e.message ?: "message 2 failed")
        }

        phase = Phase.Transport
        onEvent(Event.SendCleartext(
            InitMessages.buildNoiseHandshake(Base64Url.encode(message2.message))
        ))
        onEvent(Event.TransportReady(message2.transport, init, matched))
    }

    private fun parseEnvelope(raw: ByteArray): Pair<String, JsonObject?>? {
        val obj = try {
            json.parseToJsonElement(raw.decodeToString()).jsonObject
        } catch (_: Exception) {
            fail(
                NoiseHandshakeException.Cause.MalformedMessage,
                "cleartext frame is not a JSON object",
            )
            return null
        }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == null) {
            fail(NoiseHandshakeException.Cause.MalformedMessage, "message has no type")
            return null
        }
        return type to (obj["payload"] as? JsonObject)
    }

    private fun parsePskId(payload: ByteArray): String? = try {
        json.parseToJsonElement(payload.decodeToString())
            .jsonObject["psk_id"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun fail(reason: NoiseHandshakeException.Cause, detail: String) {
        if (phase == Phase.Failed) return
        phase = Phase.Failed
        onEvent(Event.Fail(reason, detail))
    }

    companion object {
        /**
         * Both suites are legal and a server must support both
         * (`connection.md#cipher-suites`), so this is a free choice. ChaChaPoly
         * is the software-friendly one and is the safer default across the
         * range of Android hardware this runs on; AES-GCM is worth revisiting
         * once there is a measurement showing it wins on typical devices.
         */
        val DEFAULT_SUITE = NoiseCipherSuite.CHACHA_POLY
    }
}
