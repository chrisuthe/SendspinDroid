package com.sendspindroid.sendspin.protocol.message

import android.util.Base64
import android.util.Log
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.sendspin.protocol.ServerHelloResult
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.SyncOffsetResult
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.sendspin.protocol.TrackMetadata
import org.json.JSONObject

/**
 * Parses incoming JSON messages from the SendSpin protocol.
 */
object MessageParser {
    private const val TAG = "MessageParser"

    /**
     * Parse server/hello message.
     *
     * @param payload The payload JSONObject
     * @param defaultName Fallback name if not present in payload
     * @return ServerHelloResult or null if parsing fails
     */
    fun parseServerHello(payload: JSONObject?, defaultName: String): ServerHelloResult? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }

        val serverName = payload.optString("name", defaultName)
        val serverId = payload.optString("server_id", "")
        val connectionReason = payload.optString("connection_reason", "discovery")

        val activeRolesArray = payload.optJSONArray("active_roles")
        val activeRoles = mutableListOf<String>()
        if (activeRolesArray != null) {
            for (i in 0 until activeRolesArray.length()) {
                activeRoles.add(activeRolesArray.getString(i))
            }
        }

        return ServerHelloResult(
            serverName = serverName,
            serverId = serverId,
            activeRoles = activeRoles,
            connectionReason = connectionReason
        )
    }

    /**
     * Parse server/time message and calculate NTP-style offset.
     *
     * @param payload The payload JSONObject
     * @param clientReceivedMicros Client timestamp when message was received (microseconds)
     * @return TimeMeasurement or null if parsing fails
     */
    fun parseServerTime(payload: JSONObject?, clientReceivedMicros: Long): TimeMeasurement? {
        if (payload == null) return null

        val clientTransmitted = payload.optLong("client_transmitted", 0)
        val serverReceived = payload.optLong("server_received", 0)
        val serverTransmitted = payload.optLong("server_transmitted", 0)

        if (clientTransmitted == 0L || serverReceived == 0L || serverTransmitted == 0L) {
            Log.w(TAG, "Invalid server/time payload")
            return null
        }

        // NTP-style offset calculation
        // offset = ((server_received - client_transmitted) + (server_transmitted - client_received)) / 2
        val offset = ((serverReceived - clientTransmitted) + (serverTransmitted - clientReceivedMicros)) / 2

        // Round-trip time = total elapsed - server processing time
        val rtt = (clientReceivedMicros - clientTransmitted) - (serverTransmitted - serverReceived)

        return TimeMeasurement(offset, rtt, clientReceivedMicros)
    }

    /**
     * Parse server/state message for metadata and playback state.
     *
     * @param payload The payload JSONObject
     * @return Pair of (TrackMetadata?, playbackState?) - either can be null
     */
    fun parseServerState(payload: JSONObject?): Pair<TrackMetadata?, String?> {
        if (payload == null) return Pair(null, null)

        // Extract metadata if present
        val metadata = payload.optJSONObject("metadata")?.let { metadataObj ->
            // Note: optString returns literal "null" when JSON has null value, so we filter it
            val title = metadataObj.optString("title", "").takeUnless { it == "null" } ?: ""
            val artist = metadataObj.optString("artist", "").takeUnless { it == "null" } ?: ""
            val album = metadataObj.optString("album", "").takeUnless { it == "null" } ?: ""
            val artworkUrl = metadataObj.optString("artwork_url", "").takeUnless { it == "null" } ?: ""
            val durationMs = metadataObj.optLong("duration_ms", 0)
            val positionMs = metadataObj.optLong("position_ms", 0)

            TrackMetadata(title, artist, album, artworkUrl, durationMs, positionMs)
        }

        // Extract playback state
        val state = payload.optString("state", "").takeIf { it.isNotEmpty() }

        return Pair(metadata, state)
    }

    /**
     * Parse server/command message for player control commands.
     *
     * @param payload The payload JSONObject
     * @return ServerCommandResult or null if no player command present
     */
    fun parseServerCommand(payload: JSONObject?): ServerCommandResult? {
        if (payload == null) return null

        val player = payload.optJSONObject("player") ?: return null
        val command = player.optString("command", "")

        return when (command) {
            "volume" -> {
                val volume = player.optInt("volume", -1)
                if (volume in 0..100) {
                    ServerCommandResult.Volume(volume)
                } else {
                    null
                }
            }
            "mute" -> {
                val muted = player.optBoolean("mute", false)
                ServerCommandResult.Mute(muted)
            }
            else -> {
                if (command.isNotEmpty()) {
                    ServerCommandResult.Unknown(command)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Parse group/update message.
     *
     * @param payload The payload JSONObject
     * @return GroupInfo or null if parsing fails
     */
    fun parseGroupUpdate(payload: JSONObject?): GroupInfo? {
        if (payload == null) return null

        val groupId = payload.optString("group_id", "")
        val groupName = payload.optString("group_name", "")
        val playbackState = payload.optString("playback_state", "")

        return GroupInfo(groupId, groupName, playbackState)
    }

    /**
     * Parse stream/start message.
     *
     * @param payload The payload JSONObject
     * @return StreamConfig or null if parsing fails
     */
    fun parseStreamStart(payload: JSONObject?): StreamConfig? {
        if (payload == null) return null

        val player = payload.optJSONObject("player") ?: return null

        val codec = player.optString("codec", SendSpinProtocol.AudioFormat.DEFAULT_CODEC)
        val sampleRate = player.optInt("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
        val channels = player.optInt("channels", SendSpinProtocol.AudioFormat.CHANNELS)
        val bitDepth = player.optInt("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)

        // Extract and decode codec_header if present (e.g., FLAC STREAMINFO, Opus header)
        val codecHeader = if (player.has("codec_header")) {
            try {
                val codecHeaderBase64 = player.getString("codec_header")
                Base64.decode(codecHeaderBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header", e)
                null
            }
        } else {
            null
        }

        return StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
    }

    /**
     * Parse client/sync_offset message.
     *
     * @param payload The payload JSONObject
     * @return SyncOffsetResult or null if parsing fails
     */
    fun parseSyncOffset(payload: JSONObject?): SyncOffsetResult? {
        if (payload == null) return null

        val playerId = payload.optString("player_id", "")
        val offsetMs = payload.optDouble("offset_ms", 0.0)
        val source = payload.optString("source", "unknown")

        return SyncOffsetResult(playerId, offsetMs, source)
    }
}
