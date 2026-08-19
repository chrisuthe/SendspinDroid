package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.NoiseHandshake
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.NoiseTransport
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drives one in-band re-handshake.
 *
 * `connection.md#re-handshake`: "The server may rerun the Noise handshake in
 * transport mode to swap session keys without closing the WebSocket - typically
 * to promote the trust level after a successful pairing, to switch from
 * Sentinel to a Pairing PSK, or to rotate session keys on long-running
 * connections."
 *
 * Lives in `shared` rather than the app for a specific reason: the private half
 * of [ClientIdentity] is `internal` to this module, so a responder can only be
 * constructed here. The app hands over the session facts and gets back either a
 * ready-to-send reply or a reason to close.
 *
 * Stateless and single-use. A chained re-handshake constructs a new one with
 * the previous outcome's `h`.
 *
 * @param priorHandshakeHash the prologue: the RAW 32 bytes of the previous
 *   handshake's `h`. Not its base64url text, and not the initial handshake's
 *   concatenated init bytes. A wrong value here fails [handle] at an AEAD tag
 *   check that names nothing - see `NoiseRehandshakeTest`.
 */
class RehandshakeDriver(
    private val identity: ClientIdentity,
    private val candidates: PskCandidateSet,
    private val serverId: String,
    private val serverStaticKey: ByteArray,
    private val suite: NoiseCipherSuite,
    private val priorHandshakeHash: ByteArray,
) {

    sealed interface Outcome {
        /**
         * Send [replyJson] under the CURRENT keys, then promote the channel to
         * [transport]. The order is not negotiable: "Noise message 2 is still
         * encrypted under the pre-re-handshake transport keys."
         */
        data class Reply(
            val replyJson: String,
            val transport: NoiseTransport,
            val matched: Psk,
        ) : Outcome

        /** Close the socket. The spec allows no application-level error message. */
        data class Fail(val reason: String) : Outcome
    }

    /**
     * @param data the base64url `data` field of the inbound `noise/handshake`
     */
    fun handle(data: String?): Outcome {
        if (data == null) return Outcome.Fail("noise/handshake payload has no data field")
        val message1 = Base64Url.decodeOrNull(data)
            ?: return Outcome.Fail("noise/handshake data is not base64url")

        val handshake = NoiseHandshake.responder(
            staticPrivateKey = identity.privateKeyBytes(),
            remoteStaticPublicKey = serverStaticKey,
            suite = suite,
            prologue = priorHandshakeHash,
        )

        val innerPayload = try {
            handshake.readMessage1(message1)
        } catch (e: NoiseHandshakeException) {
            // Overwhelmingly the prologue if it is not a genuine attack: the
            // ephemeral public key at the front decrypts fine either way, so
            // the first thing that notices is this tag check.
            return Outcome.Fail("re-handshake message 1 rejected: ${e.reason}")
        }

        val pskId = parsePskId(innerPayload)
            ?: return Outcome.Fail("re-handshake message 1 payload is not {\"psk_id\": ...}")

        // Selected against the candidate set as it is NOW. A record persisted
        // moments ago by a pairing has to be visible to this very selection -
        // promoting to it is the reason the server started this exchange.
        val matched = when (val selection = candidates.select(pskId, serverId)) {
            is PskCandidateSet.Selection.Matched -> selection.candidate

            PskCandidateSet.Selection.NoMatch -> return Outcome.Fail(
                "re-handshake psk_id $pskId matches none of the " +
                    "${candidates.all.size} candidates"
            )

            is PskCandidateSet.Selection.ServerIdMismatch -> return Outcome.Fail(
                "re-handshake psk_id $pskId is a record for ${selection.expected}, " +
                    "but this session is with ${selection.actual}"
            )
        }

        val message2 = try {
            handshake.writeMessage2(matched.bytes)
        } catch (e: NoiseHandshakeException) {
            return Outcome.Fail("re-handshake message 2 failed: ${e.reason}")
        }

        val reply = buildJsonObject {
            put("type", JsonPrimitive(SendSpinProtocol.MessageType.NOISE_HANDSHAKE))
            put("payload", buildJsonObject {
                put("data", JsonPrimitive(Base64Url.encode(message2.message)))
            })
        }.toString()

        return Outcome.Reply(reply, message2.transport, matched)
    }

    private fun parsePskId(payload: ByteArray): String? = runCatching {
        Json.parseToJsonElement(payload.decodeToString())
            .jsonObject["psk_id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
