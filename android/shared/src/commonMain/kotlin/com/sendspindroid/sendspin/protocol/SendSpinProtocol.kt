package com.sendspindroid.sendspin.protocol

/**
 * SendSpin Protocol constants and data classes.
 *
 * Protocol spec: https://www.sendspin-audio.com/spec/
 */
object SendSpinProtocol {
    const val VERSION = 1
    const val ENDPOINT_PATH = "/sendspin"

    /**
     * How long a pairing attempt may stay open before the client aborts it.
     *
     * "The client bounds each attempt with an attempt timeout measured from its
     * first message (recommended 2 minutes)."
     */
    const val PAIR_ATTEMPT_TIMEOUT_MS = 120_000L

    /**
     * How long the cleartext init exchange and the Noise handshake may take.
     *
     * "Implementations SHOULD apply a timeout (e.g., 30 seconds) for each side
     * to receive the next expected message during the prologue and
     * Noise-handshake phases."
     */
    const val HANDSHAKE_TIMEOUT_MS = 30_000L

    /**
     * Audio chunk header (roles/player/v1.md): 1 byte type + 8 bytes big-endian
     * int64 timestamp + 4 bytes big-endian uint32 `send_ahead`.
     */
    const val AUDIO_HEADER_SIZE_BYTES = 13

    /**
     * `send_ahead` saturates at this value instead of wrapping. Like `0`, it
     * reports that no lead was measured.
     */
    const val SEND_AHEAD_SATURATED = 4_294_967_295L

    /**
     * Binary message type identifiers.
     */
    object BinaryType {
        /**
         * A JSON message body (UTF-8). Every application message travels this
         * way once the Noise handshake completes.
         */
        const val JSON = 0

        /**
         * Fragmentation, see messaging.md#fragmentation. IDs 2-3 are reserved
         * for future use and are ignored like any other unimplemented ID.
         */
        const val FRAGMENT = 1

        const val AUDIO = 4
        const val ARTWORK_BASE = 8  // 8-11 for channels 0-3
    }

    /**
     * Noise transport framing limits.
     *
     * "A single Noise transport message is limited to 65535 bytes by the Noise
     * specification. Both defined cipher suites use a 16-byte AEAD
     * authentication tag, and the message type byte occupies the first byte of
     * the AEAD plaintext, so the application payload per frame is at most
     * 65535 - 16 - 1 = 65518 bytes." Anything larger must be fragmented (1.5).
     */
    object NoiseFraming {
        const val MAX_TRANSPORT_MESSAGE = 65535
        const val AEAD_TAG = 16
        const val TYPE_BYTE = 1
        const val MAX_PLAINTEXT = MAX_TRANSPORT_MESSAGE - AEAD_TAG          // 65519
        const val MAX_PAYLOAD = MAX_PLAINTEXT - TYPE_BYTE                   // 65518
    }

    /**
     * Audio format constants.
     */
    object AudioFormat {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BIT_DEPTH = 16
        const val DEFAULT_CODEC = "pcm"
    }

    /**
     * Artwork request constants for the `client/state` artwork object.
     */
    object Artwork {
        const val REQUEST_SIZE = 500  // Requested artwork width/height in pixels

        // The artist image is shown in the same card as the album art.
        const val ARTIST_REQUEST_SIZE = 500

        const val ALBUM_CHANNEL = 0
        const val ARTIST_CHANNEL = 1
        const val CHANNEL_COUNT = 2
    }

    /**
     * Time synchronization constants.
     *
     * Uses NTP-style best-of-N: send N packets, pick the one with lowest RTT.
     * This filters out network jitter by selecting the measurement with least congestion.
     */
    object TimeSync {
        const val INTERVAL_MS = 250L          // Wait between bursts until TimeSyncManager adapts it
        const val BURST_COUNT = 10            // Send 10 packets per burst
        const val BURST_DELAY_MS = 50L        // 50ms between burst packets
    }

    /**
     * Buffer duration targets (seconds).
     *
     * The server's BufferTracker paces delivery by wire bytes; we calculate
     * the byte cap from these durations using the highest-bitrate PCM format
     * we advertise. This keeps decoded-PCM memory bounded regardless of codec:
     * - PCM: ~DURATION seconds in memory
     * - FLAC (~50% compression): ~2x DURATION seconds, still reasonable
     */
    object Buffer {
        const val DURATION_NORMAL_SEC = 35    // 30s target + 5s sync headroom
        const val DURATION_LOW_MEM_SEC = 10
    }

    /**
     * Player timing parameters reported via client/state (roles/player/v1.md,
     * "Server Audio Send Constraints"). The server keeps at least
     * `min_buffer_ms + output_delay_ms` of audio queued, and gives the first
     * chunk of a stream at least that lead, extended to
     * `required_lead_time_ms` when the source is buffered.
     *
     * [REQUIRED_LEAD_TIME_MS] covers the first stream of a connection, when
     * AudioTrack and the decoder start from cold: on the T901 audio is not
     * flowing at full rate until about 1.2 s after `stream/start`.
     * [REQUIRED_LEAD_TIME_WARM_MS] is reported once that stream has started
     * ("a client MAY lower its reported `required_lead_time_ms` while a stream
     * is running"): a skip or a new track restarts a pipeline that is already
     * running. With 500 ms of lead a new track lost up to 63 ms of its start.
     *
     * [MIN_BUFFER_MS] is the floor of `min_buffer_ms`: what the player needs
     * in hand when chunks arrive with no delay at all. It keeps AudioTrack
     * written up to 300 ms ahead of the DAC (SyncAudioPlayer's pending target
     * plus tolerance); the other 50 ms is margin for decode and loop timing.
     * The measured arrival delay is added on top by [MinBufferEstimator].
     */
    object PlayerTiming {
        const val REQUIRED_LEAD_TIME_MS = 1500
        const val REQUIRED_LEAD_TIME_WARM_MS = 650
        const val MIN_BUFFER_MS = 350
    }

    /**
     * Protocol message type identifiers.
     */
    object MessageType {
        // Cleartext handshake. These three are the ONLY messages sent as
        // WebSocket text frames; everything below travels as a Noise ciphertext
        // in a binary frame once transport mode begins.
        const val CLIENT_INIT = "client/init"
        const val SERVER_INIT = "server/init"
        const val SERVER_ERROR = "server/error"
        const val NOISE_HANDSHAKE = "noise/handshake"

        const val CLIENT_HELLO = "client/hello"
        const val SERVER_HELLO = "server/hello"
        const val SERVER_ACTIVATE = "server/activate"
        const val PAIR_ABORT = "pair/abort"
        const val CLIENT_PAIR_PENDING = "client/pair-pending"
        const val CLIENT_PAIR_INIT = "client/pair-init"
        const val SERVER_PAIR_INIT = "server/pair-init"
        const val CLIENT_PAIR_AUTH = "client/pair-auth"
        const val SERVER_PAIR_AUTH = "server/pair-auth"
        const val CLIENT_PAIR_CONFIRM = "client/pair-confirm"
        const val SERVER_PAIR_CONFIRM = "server/pair-confirm"
        const val CLIENT_PAIR_RETRY = "client/pair-retry"
        const val CLIENT_PAIR_FINALIZE = "client/pair-finalize"
        const val SERVER_PAIR_FINALIZE = "server/pair-finalize"
        const val CLIENT_TIME = "client/time"
        const val SERVER_TIME = "server/time"
        const val CLIENT_STATE = "client/state"
        const val SERVER_STATE = "server/state"
        const val CLIENT_COMMAND = "client/command"
        const val SERVER_COMMAND = "server/command"
        const val CLIENT_GOODBYE = "client/goodbye"
        const val CLIENT_LEAVE = "client/leave"

        /** Valid regardless of the current `activities`; never gate it on them. */
        const val SERVER_UNPAIR = "server/unpair"
        const val GROUP_UPDATE = "group/update"
        const val STREAM_START = "stream/start"
        const val STREAM_END = "stream/end"
        const val STREAM_CLEAR = "stream/clear"
        const val CLIENT_SYNC_OFFSET = "client/sync_offset"
    }

    /**
     * Supported client roles.
     */
    object Roles {
        const val PLAYER = "player@v1"
        const val CONTROLLER = "controller@v1"
        const val METADATA = "metadata@v1"
        const val ARTWORK = "artwork@v1"
        const val COLOR = "color@v1"
    }

    /**
     * Role names as they appear in `stream/end` and `stream/clear`, which the
     * spec writes UNVERSIONED.
     *
     * This asymmetry is deliberate on the spec's part, not an oversight:
     * `supported_roles` and `active_roles` carry versioned identifiers
     * (`player@v1`), while `messaging.md` defines `stream/end`'s field as
     * "roles to end streams for ('player', 'artwork', 'visualizer')" and
     * `stream/clear`'s as "which roles to clear: 'player', 'visualizer', or
     * both". Comparing a versioned constant against either array therefore
     * never matches, and the message is silently ignored.
     */
    object StreamRoles {
        const val PLAYER = "player"
        const val ARTWORK = "artwork"
        const val VISUALIZER = "visualizer"
    }

    /**
     * Does [name] denote the stream role [base]?
     *
     * Matches the spec's unversioned form and tolerates a versioned one, so a
     * server that sends `player@v1` here is still understood. Being liberal in
     * what we accept costs nothing: the two forms cannot denote different
     * roles, and the failure this guards against is silence, not a wrong
     * action.
     */
    fun isStreamRole(name: String, base: String): Boolean =
        name.substringBefore('@') == base
}

/**
 * A time sync measurement from NTP-style exchange.
 */
data class TimeMeasurement(
    val offset: Long,
    val rtt: Long,
    val clientReceived: Long
)

/**
 * Progress information from server/state metadata.
 * Per spec: nested progress object with track_progress, track_duration, playback_speed.
 *
 * @param trackProgress Current position in milliseconds
 * @param trackDuration Total track duration in milliseconds
 * @param playbackSpeed Speed multiplier (1000 = 1.0x normal speed)
 */
data class TrackProgress(
    val trackProgress: Long,
    val trackDuration: Long,
    val playbackSpeed: Int = 1000  // Default to normal speed
)

/**
 * Track metadata from server/state messages.
 * Per spec: includes timestamp, nested progress, and optional fields.
 *
 * @param timestamp Server timestamp when metadata was captured (microseconds)
 * @param title Track title
 * @param artist Track artist
 * @param albumArtist Album artist (may differ from track artist for compilations)
 * @param album Album name
 * @param artworkUrl URL to album artwork
 * @param year Release year
 * @param track Track number (1-indexed)
 * @param progress Progress information (position, duration, speed)
 */
data class TrackMetadata(
    val timestamp: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val progress: TrackProgress? = null
) {
    // Null means "the server has no value for this": every `server/state`
    // carries the role's full state, so a field it omits is gone.

    // Convenience properties for backwards compatibility
    val durationMs: Long get() = progress?.trackDuration ?: 0L
    val positionMs: Long get() = progress?.trackProgress ?: 0L

    /**
     * Current track position extrapolated from this metadata snapshot,
     * using the spec formula:
     *
     *   progress + (server_now - timestamp) * playback_speed / 1_000_000
     *
     * clamped to [0, duration] (lower bound only when duration is 0 =
     * unknown/unlimited). Falls back to the raw reported position when
     * [timestamp] is missing (0), e.g. legacy servers.
     *
     * @param serverNowMicros current time on the server clock, in
     *   microseconds (from the time filter's client->server mapping)
     */
    fun progressAtServerTime(serverNowMicros: Long): Long {
        val p = progress ?: return 0L
        // A null or zero timestamp means no anchor to extrapolate from - legacy
        // servers, or a cleared field - so report the raw position.
        if (timestamp == null || timestamp == 0L) return p.trackProgress
        val elapsedMicros = serverNowMicros - timestamp
        val calculated = p.trackProgress +
                elapsedMicros * p.playbackSpeed / 1_000_000L
        return if (p.trackDuration != 0L) {
            calculated.coerceIn(0L, p.trackDuration)
        } else {
            calculated.coerceAtLeast(0L)
        }
    }
}

/**
 * Audio stream configuration from stream/start messages.
 */
data class StreamConfig(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val codecHeader: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamConfig) return false

        if (codec != other.codec) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (bitDepth != other.bitDepth) return false
        if (codecHeader != null) {
            if (other.codecHeader == null) return false
            if (!codecHeader.contentEquals(other.codecHeader)) return false
        } else if (other.codecHeader != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = codec.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + bitDepth
        result = 31 * result + (codecHeader?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Controller (group-level) state from the server/state `controller` object.
 *
 * Null means the state has no value for the field: before the first
 * `server/state` controller object, and for `seek_max_ms` whenever 'seek' is
 * not offered.
 *
 * @param supportedCommands Subset of: play, pause, stop, next, previous,
 *   volume, mute, repeat_off, repeat_one, repeat_all, shuffle, unshuffle, switch
 * @param volume Volume of the whole group, 0-100 (average of player volumes)
 * @param muted Group mute state (true only when all players are muted)
 * @param repeat Repeat mode: "off", "one", or "all"
 * @param shuffle Shuffle mode enabled/disabled
 */
data class ControllerState(
    val supportedCommands: List<String>? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val repeat: String? = null,
    val shuffle: Boolean? = null,
    /** Furthest seekable position; `controller` role, read by item 3.11. */
    val seekMaxMs: Long? = null
)

/**
 * The server/state `color` object (roles/color/v1.md): colors derived from the
 * current audio.
 *
 * Each color is packed as 0xFFRRGGBB. Null means the state has no value for
 * that color: every `server/state` carries the role's full state, so a color
 * it omits is gone.
 *
 * @param timestamp Server clock time in microseconds at which these colors
 *   take effect
 * @param backgroundDark Background for dark mode, at least 4.5:1 against white
 *   text and [onDark]
 * @param backgroundLight Background for light mode, at least 4.5:1 against
 *   black text and [onLight]
 * @param primary The dominant color. Not adjusted for contrast
 * @param accent A secondary or complementary color. Not adjusted for contrast
 * @param onDark A light color for dark backgrounds, at least 4.5:1 against
 *   black text
 * @param onLight A dark color for light backgrounds, at least 4.5:1 against
 *   white text
 */
data class ColorState(
    val timestamp: Long? = null,
    val backgroundDark: Int? = null,
    val backgroundLight: Int? = null,
    val primary: Int? = null,
    val accent: Int? = null,
    val onDark: Int? = null,
    val onLight: Int? = null
)

/**
 * Result of parsing a server/state message. A null role object was not in the
 * message, which leaves that role's state unchanged.
 */
data class ServerStateResult(
    val metadata: TrackMetadata?,
    val playbackState: String?,
    val controller: ControllerState?,
    val color: ColorState?
)

/**
 * Group information from group/update messages.
 */
data class GroupInfo(
    val groupId: String,
    val groupName: String,
    val playbackState: String
)

/**
 * Result from parsing server/command message.
 */
sealed class ServerCommandResult {
    data class Volume(val volume: Int) : ServerCommandResult()
    data class Mute(val muted: Boolean) : ServerCommandResult()
    data class SetOutputDelay(val delayMs: Int) : ServerCommandResult()
    data class Unknown(val command: String) : ServerCommandResult()
}

/**
 * Result from parsing client/sync_offset message.
 */
data class SyncOffsetResult(
    val playerId: String,
    val offsetMs: Double,
    val source: String
)
