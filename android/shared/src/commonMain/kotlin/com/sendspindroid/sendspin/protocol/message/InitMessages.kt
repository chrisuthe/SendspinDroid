package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The three cleartext handshake messages.
 *
 * Kept apart from [MessageBuilder] / [MessageParser] because they are the only
 * messages that ever travel as WebSocket text frames, and because
 * [buildClientInit]'s return value is load-bearing in a way no other builder's
 * is: its exact bytes form the first half of the Noise prologue, so the caller
 * must retain the String it got back rather than rebuilding it later.
 */
object InitMessages {

    /**
     * `client/init` - the first frame the client sends.
     *
     * Carries exactly `client_id`, `version` and `suite`;
     * `messaging.md#communication` forbids sending fields the spec does not
     * define for a message.
     *
     * **The returned String's UTF-8 bytes are the first half of the prologue.**
     * Retain this value; re-serializing an equivalent object is not guaranteed
     * to produce identical bytes, and the spec requires hashing what was
     * actually sent.
     */
    fun buildClientInit(clientId: String, suite: String): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_INIT)
        put("payload", buildJsonObject {
            put("client_id", clientId)
            put("version", SendSpinProtocol.VERSION)
            put("suite", suite)
        })
    }.toString()

    /** `noise/handshake` - carries one Noise message, base64url without padding. */
    fun buildNoiseHandshake(dataBase64Url: String): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.NOISE_HANDSHAKE)
        put("payload", buildJsonObject {
            put("data", dataBase64Url)
        })
    }.toString()

    /**
     * Parse `server/init`.
     *
     * @return null if the payload is missing, the version is not exactly 1, or
     *   `server_id` is not a 43-character base64url string decoding to 32
     *   bytes. Every one of those is a handshake failure that closes the socket
     *   with no application-level message, so there is nothing to distinguish
     *   here beyond "unusable".
     */
    fun parseServerInit(payload: JsonObject?): ServerInit? {
        if (payload == null) return null
        val serverId = payload["server_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = payload["version"]?.jsonPrimitive?.intOrNull ?: return null
        // "version is an exact-match field naming the single core message
        // format the sender speaks, not a minimum-supported version."
        if (version != SendSpinProtocol.VERSION) return null
        val key = decodeKey32(serverId) ?: return null
        return ServerInit(serverId = serverId, version = version, serverStaticKey = key)
    }

    /** Parse `noise/handshake`, returning the raw Noise bytes from `data`. */
    fun parseNoiseHandshake(payload: JsonObject?): ByteArray? {
        val data = payload?.get("data")?.jsonPrimitive?.contentOrNull ?: return null
        return Base64Url.decodeOrNull(data)
    }

    /**
     * Decode a 43-character base64url identity key.
     *
     * `connection.md#identities`: "The `client_id` and `server_id` fields are
     * the base64url-encoded (no padding) Curve25519 public keys ... 43
     * characters each."
     */
    fun decodeKey32(value: String): ByteArray? {
        if (value.length != KEY_B64_LENGTH) return null
        val bytes = Base64Url.decodeOrNull(value) ?: return null
        return if (bytes.size == 32) bytes else null
    }

    const val KEY_B64_LENGTH = 43
}

/** Parsed `server/init`. */
data class ServerInit(
    val serverId: String,
    val version: Int,
    /** The server's raw 32-byte Curve25519 static public key. */
    val serverStaticKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerInit) return false
        return serverId == other.serverId &&
            version == other.version &&
            serverStaticKey.contentEquals(other.serverStaticKey)
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + version
        result = 31 * result + serverStaticKey.contentHashCode()
        return result
    }
}
