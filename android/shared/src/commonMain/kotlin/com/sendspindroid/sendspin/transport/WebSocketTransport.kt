package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * WebSocket-based transport for local network connections using Ktor.
 *
 * Uses Ktor's WebSocket client to establish a direct connection to a
 * SendSpin server on the local network.
 *
 * ## Connection URL
 * Format: `ws://host:port/path` (e.g., `ws://192.168.1.100:8927/sendspin`)
 *
 * ## Thread Safety
 * This class is thread-safe. All state changes are atomic, and Ktor
 * handles WebSocket coroutines internally.
 *
 * @param address Server address in "host:port" format
 * @param path WebSocket path (default: "/sendspin")
 * @param pingIntervalSeconds Ping interval in seconds (default: 30, 15 in High Power Mode)
 * @param connectTimeoutMs Connect timeout in milliseconds (default: 5000)
 * @param httpClient Optional Ktor HttpClient (creates one if not provided)
 */
@OptIn(ExperimentalAtomicApi::class)
class WebSocketTransport(
    private val address: String,
    private val path: String = "/sendspin",
    pingIntervalSeconds: Long = 30,
    connectTimeoutMs: Long = 5000,
    private val httpClient: HttpClient = createDefaultClient(pingIntervalSeconds, connectTimeoutMs)
) : SendSpinTransport {

    companion object {
        private const val TAG = "WebSocketTransport"

        /**
         * Create a default Ktor HttpClient configured for SendSpin WebSocket connections.
         */
        fun createDefaultClient(
            pingIntervalSeconds: Long = 30,
            connectTimeoutMs: Long = 5000
        ): HttpClient = HttpClient {
            install(WebSockets) {
                pingIntervalMillis = pingIntervalSeconds * 1000
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                // No socket timeout for WebSocket (long-lived connection)
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }
    }

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.load()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var listener: SendSpinTransport.Listener? = null

    // Channel for outgoing messages (text or binary)
    private var outgoingChannel: Channel<OutgoingMessage>? = null

    private sealed class OutgoingMessage {
        data class Text(val text: String) : OutgoingMessage()
        data class Binary(val bytes: ByteArray) : OutgoingMessage()
    }

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (!_state.compareAndSet(TransportState.Disconnected, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Failed, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Closed, TransportState.Connecting)) {
            Log.w(TAG, "Cannot connect: already ${state}")
            return
        }

        val wsUrl = "ws://$address$path"
        Log.d(TAG, "Connecting to: $wsUrl")

        val sendChannel = Channel<OutgoingMessage>(Channel.BUFFERED)
        outgoingChannel = sendChannel

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(urlString = wsUrl) {
                    Log.d(TAG, "WebSocket connected")
                    _state.store(TransportState.Connected)
                    listener?.onConnected()

                    // Launch sender coroutine
                    val senderJob = launch {
                        try {
                            for (msg in sendChannel) {
                                when (msg) {
                                    is OutgoingMessage.Text -> send(Frame.Text(msg.text))
                                    is OutgoingMessage.Binary -> send(Frame.Binary(true, msg.bytes))
                                }
                            }
                        } catch (_: ClosedSendChannelException) {
                            // Channel closed, normal shutdown
                        } catch (_: CancellationException) {
                            // Coroutine cancelled
                        }
                    }

                    // Receive loop
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> listener?.onMessage(frame.readText())
                                is Frame.Binary -> listener?.onMessage(frame.readBytes())
                                is Frame.Close -> {
                                    val reason = closeReason.await()
                                    val code = reason?.code?.toInt() ?: 1000
                                    val msg = reason?.message ?: ""
                                    Log.d(TAG, "WebSocket closing: $code $msg")
                                    listener?.onClosing(code, msg)
                                }
                                else -> { /* Ping/Pong handled by Ktor */ }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Normal cancellation during close
                    }

                    senderJob.cancel()

                    // Session ended normally
                    val reason = closeReason.await()
                    val code = reason?.code?.toInt() ?: 1000
                    val msg = reason?.message ?: ""
                    Log.d(TAG, "WebSocket closed: $code $msg")
                    _state.store(TransportState.Closed)
                    listener?.onClosed(code, msg)
                }
            } catch (e: CancellationException) {
                // Intentional close via destroy()/close()
                Log.d(TAG, "WebSocket cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket failure: ${e.message}")
                _state.store(TransportState.Failed)
                listener?.onFailure(e, isRecoverableError(e))
            } finally {
                sendChannel.close()
                outgoingChannel = null
            }
        }
    }

    override fun send(text: String): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot send text: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Text(text)).isSuccess
    }

    override fun send(bytes: ByteArray): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot send bytes: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Binary(bytes)).isSuccess
    }

    override fun close(code: Int, reason: String) {
        Log.d(TAG, "Closing WebSocket: code=$code reason=$reason")
        outgoingChannel?.close()
        connectionJob?.cancel()
        connectionJob = null
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
        _state.store(TransportState.Closed)
        httpClient.close()
    }

    /**
     * Check if an error is likely temporary (network glitch) vs. permanent (config error).
     */
    private fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""
        val causeName = cause::class.simpleName?.lowercase() ?: ""

        return when {
            // Network errors that might resolve themselves
            causeName.contains("socketexception") -> true
            causeName.contains("eofexception") -> true
            causeName.contains("sockettimeoutexception") -> true
            causeName.contains("timeoutexception") -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true
            message.contains("timeout") -> true

            // Configuration errors that won't fix themselves
            causeName.contains("unknownhostexception") -> false
            causeName.contains("sslhandshakeexception") -> false
            causeName.contains("connectexception") -> false
            causeName.contains("noroutetohostexception") -> false
            message.contains("refused") -> false
            message.contains("unknown host") -> false
            message.contains("no route") -> false

            // Default to recoverable (optimistic)
            else -> true
        }
    }
}
