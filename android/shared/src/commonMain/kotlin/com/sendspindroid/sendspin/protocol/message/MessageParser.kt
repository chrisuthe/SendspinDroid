package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ControllerState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import com.sendspindroid.sendspin.protocol.patch
import com.sendspindroid.sendspin.protocol.StatePatch
import com.sendspindroid.sendspin.protocol.RoleUpdate
import com.sendspindroid.sendspin.protocol.Patch
import com.sendspindroid.sendspin.protocol.MetadataPatch
import com.sendspindroid.sendspin.protocol.ControllerPatch
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.sendspin.protocol.ServerHelloResult
import com.sendspindroid.sendspin.protocol.ServerStateResult
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.SyncOffsetResult
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.protocol.TrackProgress
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

object MessageParser {
    private const val TAG = "MessageParser"

    fun parseServerHello(payload: JsonObject?, defaultName: String): ServerHelloResult? {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return null
        }

        val serverName = payload.stringOrDefault("name", defaultName)
        val serverId = payload.stringOrDefault("server_id", "")
        val connectionReason = payload.stringOrDefault("connection_reason", "discovery")

        val activeRoles = payload["active_roles"]?.jsonArray?.map {
            it.jsonPrimitive.content
        } ?: emptyList()

        return ServerHelloResult(
            serverName = serverName,
            serverId = serverId,
            activeRoles = activeRoles,
            connectionReason = connectionReason
        )
    }

    fun parseServerTime(payload: JsonObject?, clientReceivedMicros: Long): TimeMeasurement? {
        if (payload == null) return null

        // Use nullable accessors so an explicit zero is distinguishable from
        // an absent field. Zero is a valid timestamp value; only an absent
        // field is grounds for rejection.
        val clientTransmitted = payload["client_transmitted"]?.jsonPrimitive?.longOrNull
        val serverReceived = payload["server_received"]?.jsonPrimitive?.longOrNull
        val serverTransmitted = payload["server_transmitted"]?.jsonPrimitive?.longOrNull

        if (clientTransmitted == null || serverReceived == null || serverTransmitted == null) {
            Log.w(TAG, "Invalid server/time payload")
            return null
        }

        val offset = ((serverReceived - clientTransmitted) + (serverTransmitted - clientReceivedMicros)) / 2
        val rtt = (clientReceivedMicros - clientTransmitted) - (serverTransmitted - serverReceived)

        return TimeMeasurement(offset, rtt, clientReceivedMicros)
    }

    /**
     * `server/state`, as a delta.
     *
     * Every field is read as a [Patch] so that absent, JSON `null` and a value
     * stay distinguishable all the way to the merge. The previous
     * implementation used `as? JsonObject` and `?: ""`, which folded the first
     * two together - so a delta carrying only `progress` arrived downstream as
     * a metadata object with empty title, artist and album, and blanked the
     * Now Playing screen on every progress tick.
     */
    fun parseServerState(payload: JsonObject?): ServerStateResult {
        if (payload == null) {
            return ServerStateResult(RoleUpdate.Absent, null, RoleUpdate.Absent)
        }

        val state = payload.stringOrDefault("state", "").takeIf { it.isNotEmpty() }

        return ServerStateResult(
            metadata = payload.roleUpdate("metadata", ::parseMetadataPatch),
            playbackState = state,
            controller = payload.roleUpdate("controller", ::parseControllerPatch),
        )
    }

    /** Absent / null / object, for a whole role. */
    private fun <S> JsonObject.roleUpdate(
        key: String,
        parse: (JsonObject) -> StatePatch<S>,
    ): RoleUpdate<S> {
        val element = this[key] ?: return RoleUpdate.Absent
        if (element is JsonNull) return RoleUpdate.Cleared
        val obj = element as? JsonObject ?: return RoleUpdate.Absent
        return RoleUpdate.Delta(parse(obj))
    }

    private fun parseMetadataPatch(obj: JsonObject): MetadataPatch = MetadataPatch(
        timestamp = obj.patch("timestamp") { it.longOrNull() },
        title = obj.patch("title", ::cleanString),
        artist = obj.patch("artist", ::cleanString),
        albumArtist = obj.patch("album_artist", ::cleanString),
        album = obj.patch("album", ::cleanString),
        artworkUrl = obj.patch("artwork_url", ::cleanString),
        year = obj.patch("year") { it.intOrNull() },
        track = obj.patch("track") { it.intOrNull() },
        progress = parseProgress(obj),
    )

    /**
     * `progress` is replaced or cleared whole, never deep-merged.
     *
     * The pre-spec Music Assistant flat fields are honoured only when
     * `progress` is absent *entirely*. An explicit `"progress": null` is a
     * clear, and falling back to the legacy fields there would resurrect a
     * position the server just told us to forget.
     */
    private fun parseProgress(obj: JsonObject): Patch<TrackProgress> {
        val element = obj["progress"]
        if (element is JsonNull) return Patch.Cleared
        if (element is JsonObject) {
            return Patch.Set(
                TrackProgress(
                    trackProgress = element.longOrDefault("track_progress", 0),
                    trackDuration = element.longOrDefault("track_duration", 0),
                    playbackSpeed = element.intOrDefault("playback_speed", 1000),
                )
            )
        }
        if (element != null) return Patch.Absent

        val hasLegacy = obj.containsKey("position_ms") || obj.containsKey("duration_ms")
        if (!hasLegacy) return Patch.Absent
        return Patch.Set(
            TrackProgress(
                trackProgress = obj.longOrDefault("position_ms", 0),
                trackDuration = obj.longOrDefault("duration_ms", 0),
                playbackSpeed = 1000,
            )
        )
    }

    private fun parseControllerPatch(obj: JsonObject): ControllerPatch = ControllerPatch(
        supportedCommands = obj.patch("supported_commands") { element ->
            (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        },
        volume = obj.patch("volume") { it.intOrNull() },
        muted = obj.patch("muted") { it.booleanOrNull() },
        repeat = obj.patch("repeat", ::cleanString),
        shuffle = obj.patch("shuffle") { it.booleanOrNull() },
        seekMaxMs = obj.patch("seek_max_ms") { it.longOrNull() },
    )

    /**
     * Music Assistant sends the four-character string "null" for an absent
     * title on some tracks. Treated as a clear rather than a title, but only
     * for strings - narrowly scoped, because any other field could legitimately
     * carry that text.
     */
    private fun cleanString(element: JsonElement): String? =
        (element as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

    private fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

    private fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    fun parseServerCommand(payload: JsonObject?): ServerCommandResult? {
        if (payload == null) return null

        val player = payload["player"]?.jsonObject ?: return null
        val command = player.stringOrDefault("command", "")

        return when (command) {
            "volume" -> {
                val volume = player.intOrDefault("volume", -1)
                if (volume in 0..100) {
                    ServerCommandResult.Volume(volume)
                } else {
                    null
                }
            }
            "mute" -> {
                val muted = player.booleanOrDefault("mute", false)
                ServerCommandResult.Mute(muted)
            }
            "set_static_delay" -> {
                // Spec: integer, 0-5000 ms.
                val delayMs = player.intOrDefault("static_delay_ms", -1)
                if (delayMs in 0..5000) {
                    ServerCommandResult.SetStaticDelay(delayMs)
                } else {
                    Log.w(TAG, "set_static_delay out of range: $delayMs")
                    null
                }
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

    fun parseGroupUpdate(payload: JsonObject?): GroupInfo? {
        if (payload == null) return null

        val groupId = payload.stringOrDefault("group_id", "")
        val groupName = payload.stringOrDefault("group_name", "")
        val playbackState = payload.stringOrDefault("playback_state", "")

        return GroupInfo(groupId, groupName, playbackState)
    }

    fun parseStreamStart(payload: JsonObject?): StreamConfig? {
        if (payload == null) return null

        val player = payload["player"]?.jsonObject ?: return null

        val codec = player.stringOrDefault("codec", SendSpinProtocol.AudioFormat.DEFAULT_CODEC)
        val sampleRate = player.intOrDefault("sample_rate", SendSpinProtocol.AudioFormat.SAMPLE_RATE)
        val channels = player.intOrDefault("channels", SendSpinProtocol.AudioFormat.CHANNELS)
        val bitDepth = player.intOrDefault("bit_depth", SendSpinProtocol.AudioFormat.BIT_DEPTH)

        val codecHeader = player["codec_header"]?.jsonPrimitive?.contentOrNull?.let { base64 ->
            try {
                Platform.base64Decode(base64)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode codec_header")
                null
            }
        }

        return StreamConfig(codec, sampleRate, channels, bitDepth, codecHeader)
    }

    /**
     * Parse client/sync_offset. NOTE: this is a Music Assistant extension
     * (GroupSync), not part of the Sendspin spec.
     */
    fun parseSyncOffset(payload: JsonObject?): SyncOffsetResult? {
        if (payload == null) return null

        val playerId = payload.stringOrDefault("player_id", "")
        val offsetMs = payload.doubleOrDefault("offset_ms", 0.0)
        val source = payload.stringOrDefault("source", "unknown")

        return SyncOffsetResult(playerId, offsetMs, source)
    }

    // Helper extensions for safe JSON access with defaults

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default

    private fun JsonObject.longOrDefault(key: String, default: Long): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: default

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.doubleOrDefault(key: String, default: Double): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: default

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default
}
