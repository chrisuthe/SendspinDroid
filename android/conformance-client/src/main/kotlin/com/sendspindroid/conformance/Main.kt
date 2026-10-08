package com.sendspindroid.conformance

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.PairingConfig
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCandidates
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.secureRandomBytes
import com.sendspindroid.sendspin.protocol.ActivationOutcome
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerActivateRules
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Sendspin conformance harness client adapter for SendSpinDroid.
 *
 * Implements the harness adapter contract (see Sendspin/conformance
 * adapters/README.md) for the client-initiated scenarios, over the encrypted
 * wire: [EncryptedSocket] runs the app's handshake driver and wire codec, and
 * everything above it is the app's real shared protocol layer -
 * ServerActivateRules, MessageBuilder, MessageParser, BinaryMessageParser and
 * SendspinTimeFilter. Other scenarios fail fast with an explanatory summary,
 * per the contract.
 *
 * The client connects unpaired, on the Sentinel PSK with unpaired access
 * enabled: the harness server approves every connecting client for exactly
 * that.
 */

private const val IMPLEMENTATION = "sendspindroid"

private const val SCENARIO_PCM = "client-initiated-pcm"
private const val SCENARIO_STATE_FORMAT_PCM = "client-initiated-state-format-pcm"
private const val SCENARIO_STATE_FORMAT_FLAC = "client-initiated-state-format-flac"

private class Args(argv: Array<String>) {
    private val map = buildMap {
        var i = 0
        while (i < argv.size - 1) {
            val key = argv[i]
            if (key.startsWith("--")) {
                put(key.removePrefix("--"), argv[i + 1])
                i += 2
            } else {
                i += 1
            }
        }
    }

    operator fun get(key: String): String? = map[key]
    fun required(key: String): String = map[key] ?: error("Missing required arg --$key")
}

/** Canonical float32 PCM hasher matching conformance/pcm.py. */
private class FloatPcmHasher {
    private val digest = MessageDigest.getInstance("SHA-256")
    var sampleCount = 0L
        private set

    fun update(pcmBytes: ByteArray, bitDepth: Int) {
        val floats: FloatArray = when (bitDepth) {
            16 -> {
                val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(pcmBytes.size / 2) { buf.short / 32768.0f }
            }
            24 -> FloatArray(pcmBytes.size / 3) { i ->
                val o = i * 3
                var v = (pcmBytes[o].toInt() and 0xFF) or
                        ((pcmBytes[o + 1].toInt() and 0xFF) shl 8) or
                        ((pcmBytes[o + 2].toInt() and 0xFF) shl 16)
                if (v and 0x800000 != 0) v = v or -0x1000000
                v / 8388608.0f
            }
            32 -> {
                val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(pcmBytes.size / 4) { buf.int / 2147483648.0f }
            }
            else -> error("Unsupported PCM bit depth: $bitDepth")
        }
        val out = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { out.putFloat(it) }
        digest.update(out.array())
        sampleCount += floats.size
    }

    fun hexdigest(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private fun writeJson(path: String, obj: JsonObject) {
    File(path).writeText(obj.toString())
}

private fun formatJson(codec: String, sampleRate: Int, channels: Int, bitDepth: Int) = buildJsonObject {
    put("codec", codec)
    put("sample_rate", sampleRate)
    put("channels", channels)
    put("bit_depth", bitDepth)
}

private fun StreamConfig.toJson() = formatJson(codec, sampleRate, channels, bitDepth)

fun main(argv: Array<String>) {
    val args = Args(argv)
    val summaryPath = args.required("summary")
    val readyPath = args.required("ready")
    val registryPath = args.required("registry")
    val scenarioId = args["scenario-id"] ?: SCENARIO_PCM
    val initiatorRole = args["initiator-role"] ?: "client"
    val preferredCodec = args["preferred-codec"] ?: "pcm"
    val clientName = args["client-name"] ?: "sendspindroid-client"
    val serverName = args["server-name"] ?: "Sendspin Conformance Server"
    val timeoutSeconds = (args["timeout-seconds"] ?: "40").toDouble()

    // Ready first: the harness waits for this file before proceeding.
    writeJson(readyPath, buildJsonObject {
        put("status", "ready")
        put("scenario_id", scenarioId)
        put("initiator_role", initiatorRole)
    })

    fun exitWithError(reason: String): Nothing {
        writeJson(summaryPath, buildJsonObject {
            put("status", "error")
            put("reason", reason)
        })
        exitProcess(1)
    }

    // What client/hello advertises, in priority order, and for the
    // renegotiation scenarios the format the client then asks for.
    fun pcm(bitDepth: Int) = MessageBuilder.FormatEntry("pcm", 8_000, 1, bitDepth)
    val formats: List<MessageBuilder.FormatEntry>
    val requestedFormat: MessageBuilder.FormatEntry?
    when {
        initiatorRole != "client" -> exitWithError(
            "sendspindroid adapter supports only client-initiated scenarios " +
                    "(got scenario_id=$scenarioId, initiator_role=$initiatorRole)"
        )
        // Same PCM-only format list the reference aiosendspin adapter
        // advertises, so the server makes the same format choice and hashes
        // are comparable.
        scenarioId == SCENARIO_PCM -> {
            formats = listOf(
                MessageBuilder.FormatEntry(preferredCodec, 8_000, 1, 16),
                MessageBuilder.FormatEntry(preferredCodec, 44_100, 2, 16),
                MessageBuilder.FormatEntry(preferredCodec, 48_000, 2, 16),
            )
            requestedFormat = null
        }
        scenarioId == SCENARIO_STATE_FORMAT_PCM -> {
            formats = listOf(pcm(24), pcm(16))
            requestedFormat = pcm(16)
        }
        scenarioId == SCENARIO_STATE_FORMAT_FLAC -> {
            requestedFormat = MessageBuilder.FormatEntry("flac", 8_000, 1, 16)
            formats = listOf(pcm(16), requestedFormat)
        }
        else -> exitWithError("sendspindroid adapter does not support scenario $scenarioId")
    }

    // Discover the server URL via the harness registry handoff.
    val deadline = System.nanoTime() + (timeoutSeconds * 1e9).toLong()
    var serverUrl: String? = null
    while (System.nanoTime() < deadline) {
        val registry = File(registryPath)
        if (registry.exists()) {
            runCatching {
                val payload = Json.parseToJsonElement(registry.readText()).jsonObject
                serverUrl = payload[serverName]?.jsonObject?.get("url")
                    ?.jsonPrimitive?.contentOrNull
            }
            if (serverUrl != null) break
        }
        Thread.sleep(100)
    }
    val url = serverUrl ?: exitWithError("Timed out waiting for '$serverName' in registry $registryPath")

    // Session state collected by the socket callbacks.
    val done = CountDownLatch(1)
    val timeFilter = SendspinTimeFilter()
    val pcmHasher = FloatPcmHasher()
    val encodedDigest = MessageDigest.getInstance("SHA-256")
    var chunkCount = 0
    var streamConfig: StreamConfig? = null
    var initialFormat: StreamConfig? = null
    var streamStartCount = 0
    var serverHelloPayload: JsonObject? = null
    var failureReason: String? = null
    var activeRoles: List<String> = emptyList()
    var activationSeen = false
    var timeRequests = 0

    fun fail(reason: String) {
        if (failureReason == null) failureReason = reason
        done.countDown()
    }

    val identity = ClientIdentity.generate()
    val pairingConfig = PairingConfig(
        secureRandomBytes(Psk.PSK_SIZE), unpairedAccessEnabled = true, dynamicPairingCodeEnabled = false,
    )
    val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    lateinit var socket: EncryptedSocket

    // client/state, built by the app's real builder. [format] is the rc1 way
    // to ask for another stream format: "When `format` changes while a
    // `player` stream is active, the server re-derives the stream format and
    // sends a `stream/start` if it changed".
    fun sendState(format: MessageBuilder.FormatEntry? = null) {
        socket.send(
            MessageBuilder.buildPlayerState(
                volume = 100, muted = false, available = true,
                playerRoleActive = SendSpinProtocol.Roles.PLAYER in activeRoles,
                format = format,
                artworkRoleActive = SendSpinProtocol.Roles.ARTWORK in activeRoles,
            )
        )
    }

    fun sendTime() {
        timeRequests += 1
        socket.send(MessageBuilder.buildClientTime(System.nanoTime() / 1000))
    }

    socket = EncryptedSocket(
        client = client,
        url = url,
        identity = identity,
        candidates = PskCandidateSet(PskCandidates.build(emptyList(), pairingConfig)),
        onFrame = fun(decoded: NoiseWireCodec.Decoded) {
            when (decoded) {
                is NoiseWireCodec.Decoded.Json -> {
                    val json = runCatching { Json.parseToJsonElement(decoded.text).jsonObject }
                        .getOrNull() ?: return
                    val payload = json["payload"] as? JsonObject
                    when (json["type"]?.jsonPrimitive?.contentOrNull) {
                        SendSpinProtocol.MessageType.SERVER_HELLO -> {
                            serverHelloPayload = payload
                            // "Sent by the client once it has received server/hello."
                            socket.send(
                                MessageBuilder.buildClientHello(
                                    deviceName = clientName,
                                    bufferCapacity = 2_000_000,
                                    manufacturer = "SendSpinDroid",
                                    supportedFormats = formats,
                                    softwareVersion = "conformance",
                                    unpairedAccessEnabled = true,
                                    // Without artwork@v1. The harness runs this
                                    // adapter for the player scenarios only, and
                                    // offering a role it does not exercise lets the
                                    // server open an artwork stream of its own
                                    // accord, which those scenarios then judge.
                                    lowMemoryMode = true,
                                )
                            )
                        }
                        SendSpinProtocol.MessageType.SERVER_ACTIVATE -> {
                            val activate = ServerActivateRules.parse(payload)
                                ?: return fail("malformed server/activate")
                            // Same rules the app applies.
                            val outcome = ServerActivateRules.evaluate(
                                activate = activate,
                                category = PskCategory.SENTINEL,
                                unpairedAccessEnabled = true,
                                previousRoles = activeRoles,
                                isFirstActivation = !activationSeen,
                                offeredPairMethods = setOf(
                                    MessageBuilder.PairMethodDescriptor.PAIRING_PSK.wireName
                                ),
                            )
                            if (outcome !is ActivationOutcome.Accept) {
                                return fail("server/activate not accepted: $outcome")
                            }
                            activationSeen = true
                            activeRoles = outcome.activeRoles
                            // Only now may the client speak, and every role
                            // this activation made active is owed its state.
                            sendState()
                            // Exercise the clock-sync path with a short burst.
                            if (timeRequests == 0) sendTime()
                        }
                        SendSpinProtocol.MessageType.SERVER_TIME -> {
                            val now = System.nanoTime() / 1000
                            MessageParser.parseServerTime(payload, now)?.let { m ->
                                timeFilter.addMeasurement(m.offset, m.rtt / 2, m.clientReceived)
                            }
                            if (timeRequests < 5) sendTime()
                        }
                        SendSpinProtocol.MessageType.STREAM_START -> {
                            val config = MessageParser.parseStreamStart(payload) ?: return
                            streamConfig = config
                            streamStartCount += 1
                            if (streamStartCount == 1) {
                                initialFormat = config
                                if (requestedFormat != null) sendState(requestedFormat)
                            }
                        }
                        SendSpinProtocol.MessageType.NOISE_HANDSHAKE ->
                            fail("a re-handshake is outside the harness scenarios")
                        else -> { /* metadata/group/stream-end and others: not verified here */ }
                    }
                }
                is NoiseWireCodec.Decoded.Typed -> {
                    val message = BinaryMessageParser.parse(decoded.type, decoded.body)
                    if (message is BinaryMessageParser.BinaryMessage.Audio) {
                        val config = streamConfig
                            ?: return fail("Received audio chunk before stream/start")
                        chunkCount += 1
                        encodedDigest.update(message.payload)
                        if (config.codec == "pcm") pcmHasher.update(message.payload, config.bitDepth)
                    }
                }
                NoiseWireCodec.Decoded.Buffered -> Unit
                is NoiseWireCodec.Decoded.ProtocolError -> fail("decode failed: ${decoded.reason}")
            }
        },
        // The server closing the connection after streaming is the normal end
        // of the scenario, and can surface as a socket failure.
        onFail = { reason -> if (chunkCount == 0) fail(reason) else done.countDown() },
        onClosed = { _, _ -> done.countDown() },
    )

    val finished = done.await((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    socket.close()
    client.dispatcher.executorService.shutdown()

    if (!finished) exitWithError("Timed out waiting for server disconnect")
    failureReason?.let { exitWithError(it) }

    val summary = buildJsonObject {
        put("status", "ok")
        put("implementation", IMPLEMENTATION)
        put("role", "client")
        put("client_name", clientName)
        put("client_id", identity.clientId)
        put("scenario_id", scenarioId)
        put("initiator_role", initiatorRole)
        put("preferred_codec", preferredCodec)
        put("peer_hello", buildJsonObject {
            put("type", "server/hello")
            serverHelloPayload?.let { put("payload", it) }
        })
        streamConfig?.let { put("stream", it.toJson()) }
        if (requestedFormat != null) {
            put("renegotiation", buildJsonObject {
                put("stream_start_count", streamStartCount)
                put("requested", with(requestedFormat) { formatJson(codec, sampleRate, channels, bitDepth) })
                initialFormat?.let { put("initial_format", it.toJson()) }
                if (streamStartCount > 1) streamConfig?.let { put("final_format", it.toJson()) }
            })
        } else {
            put("audio", buildJsonObject {
                put("audio_chunk_count", chunkCount)
                put("received_encoded_sha256", encodedDigest.digest().joinToString("") { "%02x".format(it) })
                put("received_pcm_sha256", pcmHasher.hexdigest())
                put("received_sample_count", pcmHasher.sampleCount)
            })
        }
    }
    writeJson(summaryPath, summary)
    print(File(summaryPath).readText())
    exitProcess(0)
}
