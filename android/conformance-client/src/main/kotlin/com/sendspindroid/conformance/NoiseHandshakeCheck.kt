package com.sendspindroid.conformance

import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the encrypted wire layer against a real server.
 *
 * Drives the same [SendSpinHandshakeDriver] and [NoiseWireCodec] the Android app
 * uses, over a real WebSocket, against an aiosendspin server running with
 * `allow_unencrypted=False`. Host tests prove the layer against recorded
 * transcripts; this proves it against a server that will refuse anything it does
 * not like.
 *
 * Usage: `NoiseHandshakeCheck <ws://host:port/sendspin>`
 */
object NoiseHandshakeCheck {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = args.firstOrNull() ?: "ws://127.0.0.1:8927/sendspin"
        val identity = ClientIdentity.generate()
        println("client_id: ${identity.clientId}")
        println("connecting: $url")

        val done = CountDownLatch(1)
        var failure: String? = null
        var codec: NoiseWireCodec? = null
        var sawServerHello = false

        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        lateinit var socket: WebSocket
        lateinit var driver: SendSpinHandshakeDriver

        fun fail(reason: String) {
            if (failure == null) failure = reason
            done.countDown()
        }

        driver = SendSpinHandshakeDriver(
            identity = identity,
            candidates = PskCandidateSet.sentinelOnly(),
            onEvent = { event ->
                when (event) {
                    is SendSpinHandshakeDriver.Event.SendCleartext -> {
                        println("-> text  ${event.text.take(120)}")
                        socket.send(event.text)
                    }
                    is SendSpinHandshakeDriver.Event.TransportReady -> {
                        println("HANDSHAKE OK")
                        println("   server_id : ${event.serverInit.serverId}")
                        println("   matched   : ${event.matchedPsk.category} ${event.matchedPsk.pskId}")
                        println("   h         : ${event.transport.handshakeHash.toHex().take(32)}...")
                        val c = NoiseWireCodec(event.transport)
                        codec = c
                        // First encrypted message: client/hello.
                        val hello = """{"type":"client/hello","payload":{"name":"NoiseHandshakeCheck",""" +
                            """"trust_level":"none","supported_roles":["player@v1"],""" +
                            """"player@v1_support":{"supported_formats":[{"codec":"pcm",""" +
                            """"sample_rate":48000,"channels":2,"bit_depth":16}],""" +
                            """"buffer_capacity":1680000,"supported_commands":["volume","mute"]},""" +
                            """"supported_pair_methods":[],"unpaired_access":{"enabled":true}}}"""
                        runBlocking {
                            c.encodeJson(hello).forEach { socket.send(ByteString.of(*it)) }
                        }
                        println("-> enc   client/hello (${hello.length} bytes plaintext)")
                    }
                    is SendSpinHandshakeDriver.Event.Fail ->
                        fail("${event.reason}: ${event.detail}")
                }
            },
        )

        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("socket open")
                driver.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Cleartext handshake frames. okhttp hands us a String; the raw
                // bytes are its UTF-8 encoding, which for a frame okhttp itself
                // decoded is byte-identical.
                driver.onCleartextFrame(text.toByteArray(Charsets.UTF_8))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val c = codec
                if (c == null) {
                    fail("binary frame before transport mode")
                    return
                }
                when (val decoded = c.decode(bytes.toByteArray())) {
                    is NoiseWireCodec.Decoded.Json -> {
                        println("<- enc   ${decoded.text.take(160)}")
                        if (decoded.text.contains("server/hello")) sawServerHello = true
                        // server/activate means the server accepted us.
                        if (decoded.text.contains("server/activate")) done.countDown()
                    }
                    is NoiseWireCodec.Decoded.Typed ->
                        println("<- enc   binary type=${decoded.type} ${decoded.body.size} bytes")
                    is NoiseWireCodec.Decoded.Fragment ->
                        println("<- enc   fragment type=${decoded.type}")
                    is NoiseWireCodec.Decoded.ProtocolError ->
                        fail("decode failed: ${decoded.reason}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("socket failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("socket closed: $code $reason")
                done.countDown()
            }
        })

        val finished = done.await(30, TimeUnit.SECONDS)
        socket.close(1000, "done")
        client.dispatcher.executorService.shutdown()

        println()
        val err = failure
        when {
            err != null -> {
                println("FAIL: $err")
                kotlin.system.exitProcess(1)
            }
            !finished -> {
                println("FAIL: timed out before server/activate")
                kotlin.system.exitProcess(1)
            }
            !sawServerHello -> {
                println("FAIL: handshake completed but no encrypted server/hello arrived")
                kotlin.system.exitProcess(1)
            }
            else -> println("PASS: encrypted handshake and application traffic verified")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
