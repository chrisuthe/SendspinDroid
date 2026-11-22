package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.protocol.GoodbyeReason
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

object MessageBuilder {

    /**
     * A `supported_pair_methods` entry: the method identifier is the key, and
     * the rest is its pair-method descriptor (pairing.md).
     *
     * "Every client offers at least the Pairing PSK method, and at most one
     * pairing-code method may be listed."
     */
    data class PairMethodDescriptor(
        val wireName: String,
        val locations: List<String> = emptyList(),
        val outChannels: List<String> = emptyList(),
        val formats: List<String> = emptyList(),
    ) {
        companion object {
            /**
             * `locations: ["device"]` because this client generates its own
             * Pairing PSK from a CSPRNG and shows the resulting token on screen,
             * which is what "printed on the device" describes.
             */
            val PAIRING_PSK = PairMethodDescriptor("pairing_psk", locations = listOf("device"))

            /**
             * `out_channels: ["display"]`, `formats: ["digits", "qr_code"]`.
             * The code is shown on screen, never spoken. "Clients whose
             * display can render a QR code should also offer the `qr_code`
             * emission format" (pairing.md, "Methods"), and every form factor
             * draws one. A `dynamic_pairing_code` descriptor has no
             * `locations`. "At most one" pairing-code method may be offered,
             * so advertising this one forecloses `static_pairing_code`.
             */
            val DYNAMIC_PAIRING_CODE = PairMethodDescriptor(
                wireName = "dynamic_pairing_code",
                outChannels = listOf("display"),
                formats = listOf("digits", "qr_code"),
            )
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
     * `client_id` and `version` live in `client/init`, and the role
     * configuration a client may change during the connection (player
     * commands, artwork channels) lives in `client/state`, so neither appears
     * here: `messaging.md#communication` forbids sending fields the spec does
     * not define for a message.
     *
     * @param unpairedAccessEnabled whether this client admits a server with no
     *   pairing record. This is what decides whether an unpaired connection can
     *   ever carry playback: the spec permits `'playback'` on an unpaired
     *   session "only when the client has unpaired access enabled", so omitting
     *   it leaves the server no choice but empty activities.
     * @param colorRole whether to list `color@v1`. It is not tied to
     *   [lowMemoryMode]: the role costs a few hundred bytes of JSON a track.
     *   It has no support object, which messaging.md defines only for the
     *   player, source and visualizer roles.
     */
    fun buildClientHello(
        deviceName: String,
        bufferCapacity: Int,
        manufacturer: String,
        supportedFormats: List<FormatEntry>,
        lowMemoryMode: Boolean = false,
        softwareVersion: String = "unknown",
        unpairedAccessEnabled: Boolean = true,
        supportedPairMethods: List<PairMethodDescriptor> = listOf(PairMethodDescriptor.PAIRING_PSK),
        colorRole: Boolean = true,
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_HELLO)
            put("payload", buildJsonObject {
                put("name", deviceName)
                put("supported_roles", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.PLAYER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.CONTROLLER))
                    add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.METADATA))
                    if (!lowMemoryMode) {
                        add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.ARTWORK))
                    }
                    if (colorRole) {
                        add(kotlinx.serialization.json.JsonPrimitive(SendSpinProtocol.Roles.COLOR))
                    }
                })
                put("device_info", buildJsonObject {
                    put("product_name", "SendSpinDroid")
                    put("manufacturer", manufacturer)
                    put("software_version", softwareVersion)
                })
                put("player@v1_support", buildJsonObject {
                    put("supported_formats", buildJsonArray {
                        for (fmt in supportedFormats) add(formatObject(fmt))
                    })
                    put("buffer_capacity", bufferCapacity)
                })
                // Both required by messaging.md#client--server-clienthello.
                // An object keyed by method identifier; each descriptor carries
                // only the keys its method defines.
                put("supported_pair_methods", buildJsonObject {
                    for (method in supportedPairMethods) {
                        put(method.wireName, buildJsonObject {
                            if (method.locations.isNotEmpty()) {
                                put("locations", stringArray(method.locations))
                            }
                            if (method.outChannels.isNotEmpty()) {
                                put("out_channels", stringArray(method.outChannels))
                            }
                            if (method.formats.isNotEmpty()) {
                                put("formats", stringArray(method.formats))
                            }
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

    private fun stringArray(values: List<String>) = buildJsonArray {
        for (value in values) add(kotlinx.serialization.json.JsonPrimitive(value))
    }

    private fun formatObject(format: FormatEntry) = buildJsonObject {
        put("codec", format.codec)
        put("sample_rate", format.sampleRate)
        put("channels", format.channels)
        put("bit_depth", format.bitDepth)
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
     * Build `client/pair-pending` for the Dynamic Pairing Code flow.
     *
     * Sent instead of `client/pair-init` while the attempt is gesture-gated
     * (`DynamicPairingCodeFlow` in the `AwaitingGesture` state): it carries the
     * attempt counter alone, with no commitment yet.
     */
    fun buildClientPairPending(pairingIndex: Int): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_PENDING)
        put("payload", buildJsonObject {
            put("pairing_index", pairingIndex)
        })
    }.toString()

    /**
     * Build `client/pair-init`, which starts the attempt.
     *
     * @param commitB `SHA-256("sendspin-pair-commit-v1" || nonce_B)`
     *   ([PairingCode.commit]). "Required in the Dynamic Pairing Code Flow;
     *   absent otherwise", so the Pairing PSK flow passes null.
     */
    fun buildClientPairInit(pairingIndex: Int, commitB: ByteArray? = null): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_INIT)
        put("payload", buildJsonObject {
            put("pairing_index", pairingIndex)
            if (commitB != null) put("commit_B", Base64Url.encode(commitB))
        })
    }.toString()

    /**
     * Build `client/pair-auth` for the Dynamic Pairing Code flow.
     *
     * Carries `Yb`, the client's CPace public share, as `pake_msg_2`.
     */
    fun buildClientPairAuth(pakeMsg2: ByteArray): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_AUTH)
        put("payload", buildJsonObject {
            put("pake_msg_2", Base64Url.encode(pakeMsg2))
        })
    }.toString()

    /**
     * Build `client/pair-confirm` for the Dynamic Pairing Code flow.
     *
     * @param clientKc the MCF key-confirmation tag `Tb`.
     * @param wrappedNonceB the sealed opening of `nonce_B`, so the server can
     *   check it against the `commit_B` sent in `client/pair-init`.
     */
    fun buildClientPairConfirm(clientKc: ByteArray, wrappedNonceB: ByteArray): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_CONFIRM)
        put("payload", buildJsonObject {
            put("client_kc", Base64Url.encode(clientKc))
            put("wrapped_nonce_B", Base64Url.encode(wrappedNonceB))
        })
    }.toString()

    /**
     * Build `client/pair-retry` for the Dynamic Pairing Code flow.
     *
     * `pairing.md#client--server-clientpair-retry`: "Sent in place of
     * `client/pair-confirm` when `server_kc` fails to verify and the client
     * admits another round", with "payload: `{}`".
     */
    fun buildClientPairRetry(): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_RETRY)
        put("payload", buildJsonObject { })
    }.toString()

    /**
     * Build `client/pair-finalize` for the Pairing PSK flow.
     *
     * `pairing.md#client--server-clientpair-finalize`: "In the Pairing PSK
     * Flow, it is sent immediately after `client/pair-init` without waiting
     * for a server response, carrying the PSK directly."
     */
    fun buildClientPairFinalize(longTermPsk: ByteArray): String {
        require(longTermPsk.size == 32) {
            "a Sendspin PSK is 32 bytes, got ${longTermPsk.size}"
        }
        return buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_FINALIZE)
            put("payload", buildJsonObject {
                put("long_term_psk", Base64Url.encode(longTermPsk))
            })
        }.toString()
    }

    /**
     * Build `client/pair-finalize` for the Dynamic Pairing Code flow.
     *
     * Carries the new long-term PSK as `wrapped_psk`, sealed under the CPace
     * ISK - the direct `long_term_psk` field belongs only to the Pairing PSK
     * flow, which has no CPace exchange to wrap it with.
     */
    fun buildClientPairFinalizeWrapped(wrappedPsk: ByteArray): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_PAIR_FINALIZE)
        put("payload", buildJsonObject {
            put("wrapped_psk", Base64Url.encode(wrappedPsk))
        })
    }.toString()

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

    /** Build `client/leave`, whose payload is empty. */
    fun buildLeave(): String = buildJsonObject {
        put("type", SendSpinProtocol.MessageType.CLIENT_LEAVE)
        put("payload", buildJsonObject {})
    }.toString()

    /** The typed form. Prefer this: a bare string can invent a reason. */
    fun buildGoodbye(reason: GoodbyeReason): String =
        buildGoodbye(reason.wire)

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
     * @param format the format the player currently prefers, which "MUST be one
     *   of the entries in `supported_formats`". Null means no overridden
     *   preference, and the server falls back to the hello's priority order.
     * @param artworkRoleActive whether to include the `artwork` object. A role
     *   that defines a state object must report it once active, and the server
     *   sends no artwork until it has.
     */
    fun buildPlayerState(
        volume: Int,
        muted: Boolean,
        available: Boolean,
        outputDelayMs: Double = 0.0,
        requiredLeadTimeMs: Int = SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS,
        minBufferMs: Int = SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS,
        playerRoleActive: Boolean = true,
        format: FormatEntry? = null,
        artworkRoleActive: Boolean = false,
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_STATE)
            put("payload", buildJsonObject {
                put("available", available)
                // "player?: object - only if the `player` role is active". A
                // client whose roles are all state-less still sends this
                // message - `available` alone is what unlocks the server's
                // streams.
                if (playerRoleActive) put("player", buildJsonObject {
                    put("volume", volume)
                    put("muted", muted)
                    // roles/player/v1.md: delay BEYOND the audio port, integer
                    // 0-5000, never optional. This is NOT the hardware latency
                    // we measure and compensate ourselves - the spec says this
                    // field "does not cover processing delays before the port
                    // (DAC latency, audio buffers), which the client
                    // compensates itself". Reporting that here would invite the
                    // server to compensate a second time.
                    put("output_delay_ms", outputDelayMs.roundToInt().coerceIn(0, 5000))
                    // Both timing fields are always required for players.
                    put("required_lead_time_ms", requiredLeadTimeMs)
                    put("min_buffer_ms", minBufferMs)
                    // "subset of: 'volume', 'mute', 'set_output_delay'".
                    // Advertises settability, not reportability: the spec says a
                    // server "MUST NOT treat a reported volume or muted as
                    // settable while the matching command is absent" from this
                    // list. We handle all three.
                    put("supported_commands", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("volume"))
                        add(kotlinx.serialization.json.JsonPrimitive("mute"))
                        add(kotlinx.serialization.json.JsonPrimitive("set_output_delay"))
                    })
                    if (format != null) put("format", formatObject(format))
                })
                // roles/artwork/v1.md: album art on channel 0 and the artist
                // image on channel 1, both at the size the UI renders. The
                // array is positional from channel 0.
                if (artworkRoleActive) put("artwork", buildJsonObject {
                    put("channels", buildJsonArray {
                        add(buildJsonObject {
                            put("source", "album")
                            put("format", "jpeg")
                            put("width", SendSpinProtocol.Artwork.REQUEST_SIZE)
                            put("height", SendSpinProtocol.Artwork.REQUEST_SIZE)
                        })
                        add(buildJsonObject {
                            put("source", "artist")
                            put("format", "jpeg")
                            put("width", SendSpinProtocol.Artwork.ARTIST_REQUEST_SIZE)
                            put("height", SendSpinProtocol.Artwork.ARTIST_REQUEST_SIZE)
                        })
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
     * @param positionMs only set if [command] is "seek" (0 to seek_max_ms)
     * @param offsetMs only set if [command] is "seek_relative" (signed)
     */
    fun buildCommand(
        command: String,
        volume: Int? = null,
        mute: Boolean? = null,
        positionMs: Long? = null,
        offsetMs: Long? = null,
    ): String {
        val message = buildJsonObject {
            put("type", SendSpinProtocol.MessageType.CLIENT_COMMAND)
            put("payload", buildJsonObject {
                put("controller", buildJsonObject {
                    put("command", command)
                    if (volume != null) put("volume", volume.coerceIn(0, 100))
                    if (mute != null) put("mute", mute)
                    if (positionMs != null) put("position_ms", positionMs)
                    if (offsetMs != null) put("offset_ms", offsetMs)
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
     * (each in stereo at the appropriate bit depths). Edge cases:
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
                    // Stereo only. A change of channel count needs a new
                    // AudioTrack, so it cannot be made without a gap: "A
                    // player that cannot switch its output between the
                    // formats it lists without a gap SHOULD list a single
                    // sample_rate and channels" (roles/player/v1.md). The
                    // server mixes a mono source up for us.
                    add(FormatEntry(
                        codec = codec,
                        sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                        channels = SendSpinProtocol.AudioFormat.CHANNELS,
                        bitDepth = bitDepth
                    ))
                }
            }
        }
    }
}
