package com.sendspindroid.conformance

import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.ActivationOutcome
import com.sendspindroid.sendspin.protocol.Activity
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.ServerActivateRules
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the encrypted path against a real server.
 *
 * Drives the SAME code the Android app uses - `SendSpinHandshakeDriver`,
 * `NoiseWireCodec`, `ServerActivateRules`, and the real `MessageBuilder` - over
 * a real WebSocket against an aiosendspin server running with
 * `allow_unencrypted=False`.
 *
 * The identity is **persisted** rather than generated per run. That matters:
 * an unpaired client only becomes playback-capable once the operator trusts its
 * `client_id`, so a tool that mints a fresh identity every run can never be
 * granted playback and will report empty activities forever - which is exactly
 * what earlier versions of this check did.
 *
 * Usage: `NoiseHandshakeCheck <ws://host:port/sendspin> [identity-file]`
 */
object NoiseHandshakeCheck {

    @JvmStatic
    fun main(args: Array<String>) {
        // Positional args only - a flag landing in the identity-file slot would
        // silently mint a new identity, and the server would see a client it has
        // never been told to trust.
        val positional = args.filterNot { it.startsWith("--") }
        val url = positional.getOrNull(0) ?: "ws://127.0.0.1:8927/sendspin"
        // Music Assistant only offers player setup for a client that is
        // currently online, and a 4-second connection is gone before anyone
        // can click anything. --hold keeps it up until interrupted.
        val hold = args.contains("--hold") || System.getenv("NOISECHECK_HOLD") != null
        val identityFile = File(positional.getOrNull(1) ?: ".dev/noisecheck-identity.key")
        val identity = loadOrCreateIdentity(identityFile)

        println("client_id : ${identity.clientId}")
        println("identity  : ${identityFile.path} (stable across runs)")
        println("connecting: $url")
        println()

        val done = CountDownLatch(1)
        var failure: String? = null
        var codec: NoiseWireCodec? = null
        var sawServerHello = false
        var grantedActivities: Set<Activity> = emptySet()
        var grantedRoles: List<String> = emptyList()
        var audioFrames = 0

        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        lateinit var socket: WebSocket
        lateinit var driver: SendSpinHandshakeDriver

        fun fail(reason: String) {
            if (failure == null) failure = reason
            done.countDown()
        }

        fun sendEncrypted(text: String, label: String) {
            val c = codec ?: return fail("no transport for $label")
            runBlocking { c.encodeJson(text).forEach { socket.send(ByteString.of(*it)) } }
            println("-> enc   $label")
        }

        driver = SendSpinHandshakeDriver(
            identity = identity,
            candidates = PskCandidateSet.sentinelOnly(),
            onEvent = { event ->
                when (event) {
                    is SendSpinHandshakeDriver.Event.SendCleartext -> {
                        println("-> text  ${event.text.take(90)}")
                        socket.send(event.text)
                    }
                    is SendSpinHandshakeDriver.Event.TransportReady -> {
                        println("HANDSHAKE OK  server=${event.serverInit.serverId} " +
                            "psk=${event.matchedPsk.category}")
                        codec = NoiseWireCodec(event.transport)
                        // The real builder, so this exercises what the app sends.
                        sendEncrypted(
                            MessageBuilder.buildClientHello(
                                clientId = null,          // encrypted: lives in client/init
                                deviceName = "NoiseHandshakeCheck",
                                bufferCapacity = 1_680_000,
                                manufacturer = "conformance",
                                supportedFormats = listOf(
                                    MessageBuilder.FormatEntry("pcm", 48000, 2, 16)
                                ),
                                softwareVersion = "check",
                                trustLevel = MessageBuilder.TRUST_NONE,
                                unpairedAccessEnabled = true,
                            ),
                            "client/hello",
                        )
                    }
                    is SendSpinHandshakeDriver.Event.Fail ->
                        fail("${event.reason}: ${event.detail}")
                }
            },
        )

        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = driver.start()

            override fun onMessage(webSocket: WebSocket, text: String) =
                driver.onCleartextFrame(text.toByteArray(Charsets.UTF_8))

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val c = codec ?: return fail("binary frame before transport mode")
                when (val decoded = c.decode(bytes.toByteArray())) {
                    is NoiseWireCodec.Decoded.Json -> {
                        val obj = runCatching {
                            json.parseToJsonElement(decoded.text).jsonObject
                        }.getOrNull() ?: return
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull
                        val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject
                        println("<- enc   ${decoded.text.take(140)}")

                        when (type) {
                            SendSpinProtocol.MessageType.SERVER_HELLO -> sawServerHello = true

                            SendSpinProtocol.MessageType.SERVER_ACTIVATE -> {
                                val activate = ServerActivateRules.parse(payload)
                                    ?: return fail("malformed server/activate")
                                // Same rules the app applies.
                                val outcome = ServerActivateRules.evaluate(
                                    activate = activate,
                                    category = PskCategory.SENTINEL,
                                    unpairedAccessEnabled = true,
                                    previousRoles = grantedRoles,
                                    isFirstActivation = grantedActivities.isEmpty() &&
                                        grantedRoles.isEmpty(),
                                    offeredPairMethods = setOf("pairing_psk"),
                                )
                                when (outcome) {
                                    is ActivationOutcome.Accept -> {
                                        grantedActivities = activate.activities
                                        grantedRoles = outcome.activeRoles
                                        println("         activation accepted: " +
                                            "activities=${activate.activities} roles=${outcome.activeRoles}")
                                        // Only now may we speak.
                                        sendEncrypted(
                                            MessageBuilder.buildPlayerState(
                                                volume = 100, muted = false, available = true,
                                                playerRoleActive = outcome.activeRoles
                                                    .contains("player@v1"),
                                            ),
                                            "client/state available=true",
                                        )
                                        sendEncrypted(
                                            MessageBuilder.buildClientTime(
                                                System.nanoTime() / 1000
                                            ),
                                            "client/time",
                                        )
                                        if (hold) {
                                            println("         holding connection open - " +
                                                "configure this player in Music Assistant now")
                                        } else if (Activity.PLAYBACK in activate.activities) {
                                            Thread { Thread.sleep(4000); done.countDown() }.start()
                                        } else {
                                            done.countDown()
                                        }
                                    }
                                    is ActivationOutcome.Close ->
                                        fail("activation rejected: ${outcome.goodbyeReason}")
                                    is ActivationOutcome.AbortPairing ->
                                        fail("pairing aborted: ${outcome.reason}")
                                }
                            }
                        }
                    }
                    is NoiseWireCodec.Decoded.Typed -> {
                        if (decoded.type == SendSpinProtocol.BinaryType.AUDIO) audioFrames++
                        else println("<- enc   binary type=${decoded.type} ${decoded.body.size}B")
                    }
                    is NoiseWireCodec.Decoded.Fragment ->
                        println("<- enc   fragment type=${decoded.type}")
                    is NoiseWireCodec.Decoded.ProtocolError ->
                        fail("decode failed: ${decoded.reason}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                fail("socket failure: ${t.message}")

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("socket closed: $code $reason")
                done.countDown()
            }
        })

        if (hold) {
            println()
            println("HOLDING. Configure the player in Music Assistant, then watch for a new")
            println("server/activate below. Ctrl-C to stop.")
            println()
            // A real client keeps clock sync running; without it MA may treat
            // the session as idle.
            while (true) {
                Thread.sleep(2000)
                if (codec != null && failure == null) {
                    sendEncrypted(
                        MessageBuilder.buildClientTime(System.nanoTime() / 1000),
                        "client/time",
                    )
                }
                if (done.count == 0L && failure != null) break
            }
        }

        val finished = done.await(40, TimeUnit.SECONDS)
        socket.close(1000, "done")
        client.dispatcher.executorService.shutdown()

        println()
        println("RESULT")
        println("  handshake      : ${if (codec != null) "OK" else "FAILED"}")
        println("  server/hello   : ${if (sawServerHello) "received" else "MISSING"}")
        println("  activities     : ${grantedActivities.map { it.wireName }}")
        println("  active_roles   : $grantedRoles")
        println("  audio frames   : $audioFrames")
        println()

        val err = failure
        when {
            err != null -> exitFail(err)
            !finished -> exitFail("timed out")
            !sawServerHello -> exitFail("no encrypted server/hello")
            Activity.PLAYBACK !in grantedActivities -> {
                // Not a client bug: an unpaired client is only playback-capable
                // once the operator trusts this client_id. Say so precisely so
                // nobody goes looking in the wrong place.
                println("INCOMPLETE: handshake and encrypted traffic verified, but the server")
                println("  granted no playback activity. An unpaired client needs BOTH")
                println("  unpaired_access advertised (it is - see client/hello above) AND")
                println("  the operator to trust this client_id. Start the dev server with")
                println("  --trust-all-unpaired and run this again; trust is remembered per")
                println("  client_id, which is why this tool now persists its identity.")
                kotlin.system.exitProcess(2)
            }
            else -> println("PASS: playback granted (activities=${grantedActivities.map { it.wireName }})")
        }
    }

    private fun exitFail(reason: String): Nothing {
        println("FAIL: $reason")
        kotlin.system.exitProcess(1)
    }

    private fun loadOrCreateIdentity(file: File): ClientIdentity {
        if (file.exists()) {
            val restored = ClientIdentity.fromStoredKey(file.readText().trim())
            if (restored != null) return restored
            println("WARNING: ${file.path} is unreadable; generating a new identity. " +
                "The server will not recognise it as the same client.")
        }
        val fresh = ClientIdentity.generate()
        file.parentFile?.mkdirs()
        file.writeText(ClientIdentity.encodeForStorage(fresh))
        println("generated a new identity at ${file.path}")
        return fresh
    }
}
