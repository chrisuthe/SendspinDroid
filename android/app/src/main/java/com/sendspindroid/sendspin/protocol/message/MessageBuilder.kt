package com.sendspindroid.sendspin.protocol.message

import android.os.Build
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds outgoing JSON messages for the SendSpin protocol.
 */
object MessageBuilder {

    /**
     * Build client/hello message for handshake.
     *
     * @param clientId Unique client identifier
     * @param deviceName Human-readable device name
     * @param bufferCapacity Buffer capacity in bytes
     * @return JSONObject ready to send
     */
    fun buildClientHello(
        clientId: String,
        deviceName: String,
        bufferCapacity: Int
    ): JSONObject {
        val deviceInfo = JSONObject().apply {
            put("product_name", "SendSpinDroid")
            put("manufacturer", Build.MANUFACTURER)
            put("software_version", "1.0.0")
        }

        val supportedFormats = buildSupportedFormats()

        val playerSupport = JSONObject().apply {
            put("supported_formats", supportedFormats)
            put("buffer_capacity", bufferCapacity)
            put("supported_commands", JSONArray().apply {
                put("volume")
                put("mute")
            })
        }

        val payload = JSONObject().apply {
            put("client_id", clientId)
            put("name", deviceName)
            put("version", SendSpinProtocol.VERSION)
            put("supported_roles", JSONArray().apply {
                put(SendSpinProtocol.Roles.PLAYER)
                put(SendSpinProtocol.Roles.CONTROLLER)
                put(SendSpinProtocol.Roles.METADATA)
            })
            put("device_info", deviceInfo)
            // Use legacy field name for compatibility with older servers
            put("player_support", playerSupport)
        }

        return JSONObject().apply {
            put("type", SendSpinProtocol.MessageType.CLIENT_HELLO)
            put("payload", payload)
        }
    }

    /**
     * Build client/time message for clock synchronization.
     *
     * @param clientTransmittedMicros Client timestamp in microseconds
     * @return JSONObject ready to send
     */
    fun buildClientTime(clientTransmittedMicros: Long): JSONObject {
        return JSONObject().apply {
            put("type", SendSpinProtocol.MessageType.CLIENT_TIME)
            put("payload", JSONObject().apply {
                put("client_transmitted", clientTransmittedMicros)
            })
        }
    }

    /**
     * Build client/goodbye message before disconnecting.
     *
     * @param reason Reason for disconnecting
     * @return JSONObject ready to send
     */
    fun buildGoodbye(reason: String): JSONObject {
        return JSONObject().apply {
            put("type", SendSpinProtocol.MessageType.CLIENT_GOODBYE)
            put("payload", JSONObject().apply {
                put("reason", reason)
            })
        }
    }

    /**
     * Build client/state message for player state updates.
     *
     * @param volume Volume level 0-100
     * @param muted Whether audio is muted
     * @return JSONObject ready to send
     */
    fun buildPlayerState(volume: Int, muted: Boolean): JSONObject {
        return JSONObject().apply {
            put("type", SendSpinProtocol.MessageType.CLIENT_STATE)
            put("payload", JSONObject().apply {
                put("state", "synchronized")
                put("player", JSONObject().apply {
                    put("volume", volume)
                    put("muted", muted)
                })
            })
        }
    }

    /**
     * Build client/command message for media control.
     *
     * @param command Command to send (play, pause, next, previous, switch)
     * @return JSONObject ready to send
     */
    fun buildCommand(command: String): JSONObject {
        return JSONObject().apply {
            put("type", SendSpinProtocol.MessageType.CLIENT_COMMAND)
            put("payload", JSONObject().apply {
                put("controller", JSONObject().apply {
                    put("command", command)
                })
            })
        }
    }

    /**
     * Build supported_formats array for client/hello.
     * Formats are ordered by user preference (preferred codec first).
     *
     * @return JSONArray of supported format objects
     */
    fun buildSupportedFormats(): JSONArray {
        val formats = JSONArray()
        val preferredCodec = UserSettings.getPreferredCodec()

        // Get list of codecs to advertise, with preferred first
        val codecOrder = mutableListOf<String>()

        // Add preferred codec first
        if (AudioDecoderFactory.isCodecSupported(preferredCodec)) {
            codecOrder.add(preferredCodec)
        }

        // Add remaining codecs
        for (codec in listOf("pcm", "flac", "opus")) {
            if (codec != preferredCodec && AudioDecoderFactory.isCodecSupported(codec)) {
                codecOrder.add(codec)
            }
        }

        // Build format entries for each codec (stereo and mono)
        for (codec in codecOrder) {
            // Stereo format
            formats.put(JSONObject().apply {
                put("codec", codec)
                put("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
                put("channels", SendSpinProtocol.AudioFormat.CHANNELS)
                put("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)
            })

            // Mono format
            formats.put(JSONObject().apply {
                put("codec", codec)
                put("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
                put("channels", 1)
                put("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)
            })
        }

        return formats
    }

    /**
     * Serialize a JSONObject to a string, fixing escaped slashes.
     *
     * Android's JSONObject escapes forward slashes as \/, which some servers don't like.
     *
     * @param message The JSONObject to serialize
     * @return String representation with unescaped slashes
     */
    fun serialize(message: JSONObject): String {
        return message.toString().replace("\\/", "/")
    }
}
