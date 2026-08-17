package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

object MessageBuilder {

    /** `trust_level` values (README.md#definitions). Ordered none < user. */
    const val TRUST_NONE = "none"
    const val TRUST_USER = "user"

    /**
     * A `supported_pair_methods` entry.
     *
     * "Every client implements at least the Pairing PSK method." The PIN methods
     * are optional for clients and are deferred to item 4.4 (#220).
     */
    data class PairMethodDescriptor(
        val wireName: String,
        val locations: List<String>,
    ) {
        companion object {
            /**
             * `locations: ["device"]` because this client generates its own
             * Pairing PSK from a CSPRNG and shows the resulting token on screen,
             * which is what "printed on the device" describes. Item 0.3 (#191)
             * confirmed Music Assistant renders that hint accurately.
             */
            val PAIRING_PSK = PairMethodDescriptor("pairing_psk", listOf("device"))
        }
    }

    data class FormatEntry(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int
    )

    /**
     * Build `client/hello`.
     *
     * @param clientId legacy dialect only. `client_id` and `version` moved to
     *   `client/init` when encryption landed, and `messaging.md#communication`
     *   forbids sending fields the spec does not define for a message - so on
     *   an encrypted session this must be null and both fields are omitted.
     * @param trustLevel `'user'` when a pairing record exists for this server,
     *   `'none'` otherwise. Required.
     * @param unpairedAccessEnabled whether this client admits a server with no
     *   pairing record. This is what decides whether an unpaired connection can
     *   ever carry playback: the spec permits `['playback']` on a Sentinel-keyed
     *   session "only when the client has unpaired access enabled", so omitting
     *   it leaves the server no choice but empty activities.
     */
    fun buildClientHello(
        clientId: String?,
        deviceName: String,
        bufferCapacity: Int,
        manufacturer: String,
        supportedFormats: List<FormatEntry>,
        lowMemoryMode: Boolean = false,
        softwareVersion: String = "unknown",
        trustLevel: String = TRUST_NONE,
        unpairedAccessEnabled: Boolean = true,
        supportedPairMethods: List<PairMethodDescriptor> = listOf(PairMethodDescriptor.PAIRING_PSK),
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_HELLO)
            put("payload", buildJsonObject {
                // Legacy-only. On an encrypted session these live in client/init.
                if (clientId != null) {
                    put("client_id", clientId)
                    put("version", SendSpinProtocol.VERSION)
                }
                put("name", deviceName)
                put("trust_level", trustLevel)
                put("supported_roles", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.PLAYER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.CONTROLLER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.METADATA))
                    if (!lowMemoryMode) {
                        add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.ARTWORK))
                    }
                })
                put("device_info", buildJsonObject {
                    put("product_name", "SendSpinDroid")
                    put("manufacturer", manufacturer)
                    put("software_version", softwareVersion)
                })
                put("player@v1_support", buildJsonObject {
                    put("supported_formats", buildJsonArray {
                        for (fmt in supportedFormats) {
                            add(buildJsonObject {
                                put("codec", fmt.codec)
                                put("sample_rate", fmt.sampleRate)
                                put("channels", fmt.channels)
                                put("bit_depth", fmt.bitDepth)
                            })
                        }
                    })
                    put("buffer_capacity", bufferCapacity)
                    put("supported_commands", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("volume"))
                        add(kotlinx.serialization.json.JsonPrimitive("mute"))
                    })
                })
                if (!lowMemoryMode) {
                    put("artwork@v1_support", buildJsonObject {
                        put("channels", buildJsonArray {
                            add(buildJsonObject {
                                put("source", "album")
                                put("format", "jpeg")
                                put("media_width", SendSpinProtocol.Artwork.REQUEST_SIZE)
                                put("media_height", SendSpinProtocol.Artwork.REQUEST_SIZE)
                            })
                        })
                    })
                }
                // Both required by messaging.md#client--server-clienthello.
                put("supported_pair_methods", buildJsonArray {
                    for (method in supportedPairMethods) {
                        add(buildJsonObject {
                            put("method", method.wireName)
                            put("locations", buildJsonArray {
                                for (location in method.locations) {
                                    add(kotlinx.serialization.json.JsonPrimitive(location))
                                }
                            })
                        })
                    }
                })
                put("unpaired_access", buildJsonObject {
                    put("enabled", unpairedAccessEnabled)
                })
            })
        }
        return message.toString()
    }

    fun buildClientTime(clientTransmittedMicros: Long): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_TIME)
            put("payload", buildJsonObject {
                put("client_transmitted", clientTransmittedMicros)
            })
        }
        return message.toString()
    }

    /**
     * `pair/abort`.
     *
     * Only `concurrent_attempt` closes the connection after sending; every
     * other reason leaves it open so the server can re-activate. Item 2.9
     * (#226) owns the full enum and the attempt state machine.
     */
    fun buildPairAbort(reason: String): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.PAIR_ABORT)
        put("payload", buildJsonObject { put("reason", reason) })
    }.toString()

    fun buildGoodbye(reason: String): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_GOODBYE)
            put("payload", buildJsonObject {
                put("reason", reason)
            })
        }
        return message.toString()
    }

    /**
     * Build `client/state`.
     *
     * @param available whether this client can participate in playback. The
     *   spec renamed the old `state` string to a boolean (#115), so
     *   `"synchronized"` / `"error"` / `"external_source"` no longer exist on
     *   the wire. A player reports `true` only once its clock is synchronised:
     *   "A player MUST NOT report `available: true` until its time filter has
     *   converged enough to begin scheduling playback."
     *
     *   `false` now means only one thing - the client's output is in use by an
     *   external system (messaging.md#external-source-handling). It is NOT the
     *   way to report a sync problem, which is why the convergence gate lives
     *   at the call site rather than here.
     */
    fun buildPlayerState(
        volume: Int,
        muted: Boolean,
        available: Boolean,
        staticDelayMs: Double = 0.0,
        requiredLeadTimeMs: Int = SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS,
        minBufferMs: Int = SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_STATE)
            put("payload", buildJsonObject {
                put("available", available)
                put("player", buildJsonObject {
                    put("volume", volume)
                    put("muted", muted)
                    // Spec: integer, range 0-5000, negative values not
                    // supported. Locally we still apply the full signed
                    // value (user sync offset can be negative); only the
                    // reported field is clamped.
                    put("static_delay_ms", staticDelayMs.roundToInt().coerceIn(0, 5000))
                    // Both timing fields are always required for players.
                    put("required_lead_time_ms", requiredLeadTimeMs)
                    put("min_buffer_ms", minBufferMs)
                    // Declares that we handle server/command set_static_delay.
                    put("supported_commands", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("set_static_delay"))
                    })
                })
            })
        }
        return message.toString()
    }

    /**
     * Build a client/command controller message.
     *
     * @param volume only set if [command] is "volume" (0-100)
     * @param mute only set if [command] is "mute"
     */
    fun buildCommand(command: String, volume: Int? = null, mute: Boolean? = null): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_COMMAND)
            put("payload", buildJsonObject {
                put("controller", buildJsonObject {
                    put("command", command)
                    if (volume != null) put("volume", volume.coerceIn(0, 100))
                    if (mute != null) put("mute", mute)
                })
            })
        }
        return message.toString()
    }

    /**
     * Build a stream/request-format message for the player role.
     *
     * All fields optional; omitted fields keep their current value on the
     * server. The server responds with stream/start carrying the new format.
     */
    fun buildStreamRequestFormat(
        codec: String? = null,
        sampleRate: Int? = null,
        channels: Int? = null,
        bitDepth: Int? = null
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.STREAM_REQUEST_FORMAT)
            put("payload", buildJsonObject {
                put("player", buildJsonObject {
                    if (codec != null) put("codec", codec)
                    if (sampleRate != null) put("sample_rate", sampleRate)
                    if (channels != null) put("channels", channels)
                    if (bitDepth != null) put("bit_depth", bitDepth)
                })
            })
        }
        return message.toString()
    }

    /**
     * Calculate buffer_capacity (wire bytes) from target duration and format list.
     *
     * Uses the highest-bitrate PCM entry we advertise as the basis, so the cap
     * is tight for PCM and gives compressed codecs proportionally more seconds
     * of look-ahead (but bounded decoded memory).
     */
    fun calculateBufferCapacity(formats: List<FormatEntry>, durationSec: Int): Int {
        val maxPcmBytesPerSec = formats
            .filter { it.codec == "pcm" }
            .maxOfOrNull { it.sampleRate * it.channels * (it.bitDepth / 8) }
            ?: (SendSpinProtocol.AudioFormat.SAMPLE_RATE
                    * SendSpinProtocol.AudioFormat.CHANNELS
                    * (SendSpinProtocol.AudioFormat.BIT_DEPTH / 8))
        return durationSec * maxPcmBytesPerSec
    }

    /**
     * Build the supported_formats list for the client/hello message.
     *
     * The advertised list never contains a codec other than [preferredCodec] or
     * `"pcm"`, and when both are present the preferred codec appears first
     * (each with stereo+mono variants at the appropriate bit depths). Edge cases:
     * - If [preferredCodec] is not supported on this device, it is silently dropped
     *   and only PCM is advertised. The Settings UI surfaces supported codecs
     *   explicitly; this fallback exists so a connection can still succeed even
     *   if support state was stale at the time the preference was set.
     * - If [preferredCodec] is `"pcm"`, PCM is advertised once (not twice).
     * - If neither the preferred codec nor PCM is supported (shouldn't happen on
     *   any real Android device; PCM is always supported), the list is empty.
     *
     * Compressed codecs (FLAC, Opus) are always advertised at 16-bit. PCM is
     * advertised at every entry in [supportedBitDepths], highest first (so the
     * server picks the best-quality match).
     */
    fun buildSupportedFormats(
        preferredCodec: String,
        isCodecSupported: (String) -> Boolean,
        supportedBitDepths: List<Int> = listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH)
    ): List<FormatEntry> {
        val codecOrder = mutableListOf<String>()

        if (preferredCodec != "pcm" && isCodecSupported(preferredCodec)) {
            codecOrder.add(preferredCodec)
        }

        if (isCodecSupported("pcm")) {
            codecOrder.add("pcm")
        }

        return buildList {
            for (codec in codecOrder) {
                // Higher bit depths only apply to PCM; compressed codecs
                // (FLAC, Opus) decode to 16-bit PCM regardless of source depth.
                val depths = if (codec == "pcm") {
                    supportedBitDepths.sortedDescending()
                } else {
                    listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH)
                }
                for (bitDepth in depths) {
                    // Stereo
                    add(FormatEntry(
                        codec = codec,
                        sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                        channels = SendSpinProtocol.AudioFormat.CHANNELS,
                        bitDepth = bitDepth
                    ))
                    // Mono
                    add(FormatEntry(
                        codec = codec,
                        sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                        channels = SendSpinProtocol.AudioFormat.CHANNELS_MONO,
                        bitDepth = bitDepth
                    ))
                }
            }
        }
    }
}
