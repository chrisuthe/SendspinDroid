package com.sendspindroid.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fake SendSpin server that drives a FakeTransport.
 *
 * Simulates server-side behavior for E2E testing:
 * - Responds to client/hello with server/hello
 * - Sends server/state and group/update messages
 * - Generates binary audio chunks with proper header format
 * - Handles time sync messages
 *
 * Usage:
 * ```
 * val transport = FakeTransport()
 * val server = FakeSendSpinServer(transport)
 *
 * // In test setup, inject transport into SendSpinClient via reflection
 * // then:
 * server.completeHandshake()
 * server.sendPlayingState()
 * server.sendAudioChunk(timestampMicros, pcmData)
 * ```
 */
class FakeSendSpinServer(
    private val transport: FakeTransport,
    val serverName: String = "TestServer",
    val serverId: String = "test-server-id"
) {

    companion object {
        // Binary message types (from protocol spec)
        const val MSG_TYPE_AUDIO = 4
        const val MSG_TYPE_ARTWORK_0 = 8
        const val MSG_TYPE_VISUALIZER = 16

        // Default audio format
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_BIT_DEPTH = 16
        const val DEFAULT_CODEC = "pcm"
    }

    /** Track whether handshake was completed. */
    var handshakeCompleted = false
        private set

    /** Messages received from the client (parsed from transport.sentTextMessages). */
    val receivedMessages: List<String> get() = transport.sentTextMessages.toList()

    /**
     * Simulate server connection and complete the full handshake:
     * 1. Transport becomes connected
     * 2. Wait for client/hello (auto-sent by SendSpinClient.TransportEventListener.onConnected)
     * 3. Send server/hello
     */
    fun completeHandshake(
        protocolVersion: Int = 1,
        activeRoles: List<String> = listOf("player")
    ) {
        // Step 1: simulate transport connected (triggers client/hello)
        transport.simulateConnected()

        // Step 2: send server/hello back
        sendServerHello(protocolVersion, activeRoles)
        handshakeCompleted = true
    }

    /**
     * Send a server/hello message.
     */
    fun sendServerHello(
        protocolVersion: Int = 1,
        activeRoles: List<String> = listOf("player")
    ) {
        val msg = buildJsonObject {
            put("type", "server/hello")
            put("payload", buildJsonObject {
                put("name", serverName)
                put("server_id", serverId)
                put("protocol_version", protocolVersion)
                put("active_roles", buildJsonArray {
                    activeRoles.forEach { add(JsonPrimitive(it)) }
                })
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a server/state message with track metadata and playback state.
     *
     * Protocol format: payload contains "state" (string) and "metadata" (object).
     * The metadata object contains track info and a "progress" sub-object.
     * See MessageParser.parseServerState() for the expected structure.
     */
    fun sendServerState(
        playbackState: String = "playing",
        title: String = "Test Track",
        artist: String = "Test Artist",
        album: String = "Test Album",
        durationMs: Long = 240000,
        positionMs: Long = 0,
        artworkUrl: String = ""
    ) {
        val msg = buildJsonObject {
            put("type", "server/state")
            put("payload", buildJsonObject {
                put("state", playbackState)
                put("metadata", buildJsonObject {
                    put("title", title)
                    put("artist", artist)
                    put("album", album)
                    put("artwork_url", artworkUrl)
                    put("timestamp", 0)
                    put("progress", buildJsonObject {
                        put("track_progress", positionMs)
                        put("track_duration", durationMs)
                        put("playback_speed", 1000)
                    })
                })
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a group/update message.
     */
    fun sendGroupUpdate(
        groupId: String = "group-1",
        groupName: String = "Living Room",
        playbackState: String = "playing"
    ) {
        val msg = buildJsonObject {
            put("type", "group/update")
            put("payload", buildJsonObject {
                put("group_id", groupId)
                put("group_name", groupName)
                put("playback_state", playbackState)
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a stream/start message to configure the audio stream.
     *
     * Protocol format: payload contains a "player" sub-object with audio format.
     * See MessageParser.parseStreamStart() for the expected structure.
     */
    fun sendStreamStart(
        codec: String = DEFAULT_CODEC,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitDepth: Int = DEFAULT_BIT_DEPTH
    ) {
        val msg = buildJsonObject {
            put("type", "stream/start")
            put("payload", buildJsonObject {
                put("player", buildJsonObject {
                    put("codec", codec)
                    put("sample_rate", sampleRate)
                    put("channels", channels)
                    put("bit_depth", bitDepth)
                })
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a stream/stop message.
     */
    fun sendStreamEnd() {
        val msg = buildJsonObject {
            put("type", "stream/end")
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a binary audio chunk with proper protocol header.
     *
     * Header format: 1 byte type + 8 byte big-endian int64 timestamp
     * Followed by PCM audio data.
     */
    fun sendAudioChunk(timestampMicros: Long, audioData: ByteArray) {
        val header = ByteBuffer.allocate(9)
        header.order(ByteOrder.BIG_ENDIAN)
        header.put(MSG_TYPE_AUDIO.toByte())
        header.putLong(timestampMicros)

        val message = ByteArray(9 + audioData.size)
        System.arraycopy(header.array(), 0, message, 0, 9)
        System.arraycopy(audioData, 0, message, 9, audioData.size)

        transport.simulateBinaryMessage(message)
    }

    /**
     * Send artwork data on a specific channel.
     */
    fun sendArtwork(channel: Int, imageData: ByteArray) {
        val type = 8 + channel
        val header = ByteBuffer.allocate(9)
        header.order(ByteOrder.BIG_ENDIAN)
        header.put(type.toByte())
        header.putLong(0L) // timestamp unused for artwork

        val message = ByteArray(9 + imageData.size)
        System.arraycopy(header.array(), 0, message, 0, 9)
        System.arraycopy(imageData, 0, message, 9, imageData.size)

        transport.simulateBinaryMessage(message)
    }

    /**
     * Clear artwork on a specific channel (empty payload).
     */
    fun clearArtwork(channel: Int) {
        sendArtwork(channel, ByteArray(0))
    }

    /**
     * Send a server/time response for clock synchronization.
     *
     * Responds to the client's client/time message with a server timestamp.
     */
    fun sendTimeResponse(clientTimestamp: Long, serverTimestamp: Long) {
        val msg = buildJsonObject {
            put("type", "server/time")
            put("payload", buildJsonObject {
                put("client_time", clientTimestamp)
                put("server_time", serverTimestamp)
            })
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a proxy auth_ok response.
     */
    fun sendAuthOk() {
        val msg = buildJsonObject {
            put("type", "auth_ok")
            put("message", "Authenticated")
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Send a proxy auth_failed response.
     */
    fun sendAuthFailed(message: String = "Invalid token") {
        val msg = buildJsonObject {
            put("type", "auth_failed")
            put("message", message)
        }
        transport.simulateTextMessage(msg.toString())
    }

    /**
     * Generate a block of silent PCM audio data.
     *
     * @param durationMs Duration in milliseconds
     * @param sampleRate Sample rate (default 48000)
     * @param channels Number of channels (default 2)
     * @param bitDepth Bit depth (default 16)
     * @return ByteArray of silence
     */
    fun generateSilence(
        durationMs: Int = 100,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitDepth: Int = DEFAULT_BIT_DEPTH
    ): ByteArray {
        val bytesPerSample = bitDepth / 8
        val totalSamples = (sampleRate * durationMs / 1000) * channels
        return ByteArray(totalSamples * bytesPerSample)
    }

    /**
     * Check if the client sent a client/hello message.
     */
    fun clientSentHello(): Boolean {
        return transport.hasSentMessageContaining("client/hello")
    }

    /**
     * Check if the client sent a client/state message.
     */
    fun clientSentState(): Boolean {
        return transport.hasSentMessageContaining("client/state")
    }

    /**
     * Check if the client sent a specific command.
     */
    fun clientSentCommand(command: String): Boolean {
        return transport.findSentMessages { msg ->
            try {
                val json = Json.parseToJsonElement(msg).jsonObject
                val type = json["type"]?.jsonPrimitive?.contentOrNull
                type == "client/command" &&
                    json["payload"]?.jsonObject?.get("command")?.jsonPrimitive?.contentOrNull == command
            } catch (e: Exception) {
                false
            }
        }.isNotEmpty()
    }

    /**
     * Check if the client sent a goodbye message.
     */
    fun clientSentGoodbye(): Boolean {
        return transport.hasSentMessageContaining("client/goodbye")
    }
}
