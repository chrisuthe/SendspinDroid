package com.sendspindroid.conformance

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Sendspin conformance harness client adapter for SendSpinDroid.
 *
 * Implements the harness adapter contract (see Sendspin/conformance
 * adapters/README.md) for the `client-initiated-pcm` scenario, driving the
 * app's real shared protocol layer: MessageBuilder, MessageParser,
 * BinaryMessageParser, and SendspinTimeFilter. Other scenarios fail fast
 * with an explanatory summary, per the contract.
 */

private const val IMPLEMENTATION = "sendspindroid"

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

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun main(argv: Array<String>) {
    val args = Args(argv)
    val summaryPath = args.required("summary")
    val readyPath = args.required("ready")
    val registryPath = args.required("registry")
    val scenarioId = args["scenario-id"] ?: "client-initiated-pcm"
    val initiatorRole = args["initiator-role"] ?: "client"
    val preferredCodec = args["preferred-codec"] ?: "pcm"
    val clientName = args["client-name"] ?: "sendspindroid-client"
    val clientId = args["client-id"] ?: "sendspindroid-client-id"
    val serverName = args["server-name"] ?: "Sendspin Conformance Server"
    val timeoutSeconds = (args["timeout-seconds"] ?: "40").toDouble()

    // Ready first: the harness waits for this file before proceeding.
    writeJson(readyPath, buildJsonObject {
        put("status", "ready")
        put("scenario_id", scenarioId)
        put("initiator_role", initiatorRole)
    })

    if (scenarioId != "client-initiated-pcm" || initiatorRole != "client") {
        writeJson(summaryPath, buildJsonObject {
            put("status", "error")
            put(
                "reason",
                "sendspindroid adapter currently supports only the client-initiated-pcm scenario " +
                        "(got scenario_id=$scenarioId, initiator_role=$initiatorRole)"
            )
        })
        exitProcess(1)
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
    if (serverUrl == null) {
        writeJson(summaryPath, buildJsonObject {
            put("status", "error")
            put("reason", "Timed out waiting for '$serverName' in registry $registryPath")
        })
        exitProcess(1)
    }

    // Session state collected by the listener.
    val done = CountDownLatch(1)
    val timeFilter = SendspinTimeFilter()
    val pcmHasher = FloatPcmHasher()
    val encodedDigest = MessageDigest.getInstance("SHA-256")
    var chunkCount = 0
    var streamConfig: StreamConfig? = null
    var serverHelloPayload: JsonObject? = null
    var failureReason: String? = null

    // Same PCM-only format list the reference aiosendspin adapter advertises,
    // so the server makes the same format choice and hashes are comparable.
    val formats = listOf(
        MessageBuilder.FormatEntry(preferredCodec, 8_000, 1, 16),
        MessageBuilder.FormatEntry(preferredCodec, 44_100, 2, 16),
        MessageBuilder.FormatEntry(preferredCodec, 48_000, 2, 16),
    )

    val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                MessageBuilder.buildClientHello(
                    clientId = clientId,
                    deviceName = clientName,
                    bufferCapacity = 2_000_000,
                    manufacturer = "SendSpinDroid",
                    supportedFormats = formats,
                    softwareVersion = "conformance"
                )
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = json["payload"] as? JsonObject

            when (type) {
                SendSpinProtocol.MessageType.SERVER_HELLO -> {
                    serverHelloPayload = payload
                    // Initial client/state per spec, built by the app's real builder.
                    webSocket.send(MessageBuilder.buildPlayerState(100, false, "synchronized", 0.0))
                    // Exercise the clock-sync path with a short burst.
                    thread(isDaemon = true) {
                        repeat(5) {
                            webSocket.send(MessageBuilder.buildClientTime(System.nanoTime() / 1000))
                            Thread.sleep(100)
                        }
                    }
                }
                SendSpinProtocol.MessageType.SERVER_TIME -> {
                    val now = System.nanoTime() / 1000
                    MessageParser.parseServerTime(payload, now)?.let { m ->
                        timeFilter.addMeasurement(m.offset, m.rtt / 2, m.clientReceived, m.rtt)
                    }
                }
                SendSpinProtocol.MessageType.STREAM_START -> {
                    streamConfig = MessageParser.parseStreamStart(payload)
                }
                else -> { /* metadata/group/stream-end and others: not verified here */ }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val message = BinaryMessageParser.parse(bytes.toByteArray()) ?: return
            if (message is BinaryMessageParser.BinaryMessage.Audio) {
                val config = streamConfig
                if (config == null) {
                    failureReason = "Received audio chunk before stream/start"
                    done.countDown()
                    return
                }
                chunkCount += 1
                encodedDigest.update(message.payload)
                pcmHasher.update(message.payload, config.bitDepth)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The server closing the TCP connection after streaming surfaces
            // as EOFException here; that is the normal end of the scenario.
            if (chunkCount == 0) failureReason = "WebSocket failure: ${t.message}"
            done.countDown()
        }
    }

    val ws = client.newWebSocket(Request.Builder().url(serverUrl!!).build(), listener)

    val finished = done.await((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    ws.cancel()
    client.dispatcher.executorService.shutdown()

    if (!finished) {
        writeJson(summaryPath, buildJsonObject {
            put("status", "error")
            put("reason", "Timed out waiting for server disconnect")
        })
        exitProcess(1)
    }
    if (failureReason != null) {
        writeJson(summaryPath, buildJsonObject {
            put("status", "error")
            put("reason", failureReason!!)
        })
        exitProcess(1)
    }

    val summary = buildJsonObject {
        put("status", "ok")
        put("implementation", IMPLEMENTATION)
        put("role", "client")
        put("client_name", clientName)
        put("client_id", clientId)
        put("scenario_id", scenarioId)
        put("initiator_role", initiatorRole)
        put("preferred_codec", preferredCodec)
        put("peer_hello", buildJsonObject {
            put("type", "server/hello")
            serverHelloPayload?.let { put("payload", it) }
        })
        streamConfig?.let { config ->
            put("stream", buildJsonObject {
                put("codec", config.codec)
                put("sample_rate", config.sampleRate)
                put("channels", config.channels)
                put("bit_depth", config.bitDepth)
            })
        }
        put("audio", buildJsonObject {
            put("audio_chunk_count", chunkCount)
            put("received_encoded_sha256", encodedDigest.digest().joinToString("") { "%02x".format(it) })
            put("received_pcm_sha256", pcmHasher.hexdigest())
            put("received_sample_count", pcmHasher.sampleCount)
        })
    }
    writeJson(summaryPath, summary)
    print(File(summaryPath).readText())
    exitProcess(0)
}
