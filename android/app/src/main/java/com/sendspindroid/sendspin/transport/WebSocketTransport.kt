package com.sendspindroid.sendspin.transport

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

/**
 * WebSocket-based transport for local network connections.
 *
 * Uses OkHttp's WebSocket client to establish a direct connection to a
 * SendSpin server on the local network.
 *
 * ## Connection URL
 * Format: `ws://host:port/path` (e.g., `ws://192.168.1.100:8927/sendspin`)
 *
 * ## Thread Safety
 * This class is thread-safe. All state changes are atomic, and OkHttp
 * handles WebSocket threading internally.
 *
 * @param address Server address in "host:port" format
 * @param path WebSocket path (default: "/sendspin")
 * @param okHttpClient Optional OkHttpClient instance (creates one if not provided)
 */
class WebSocketTransport(
    private val address: String,
    private val path: String = "/sendspin",
    private val okHttpClient: OkHttpClient = createDefaultClient()
) : SendSpinTransport {

    companion object {
        private const val TAG = "WebSocketTransport"

        /**
         * Create a default OkHttpClient configured for SendSpin WebSocket connections.
         *
         * - Short connect timeout (5s) for fast failure during reconnection
         * - No read timeout (required for WebSocket)
         * - 30s ping interval to keep connection alive
         */
        fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Required for WebSocket
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.get()

    private var webSocket: WebSocket? = null
    private var listener: SendSpinTransport.Listener? = null

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

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
    }

    override fun send(text: String): Boolean {
        val ws = webSocket ?: run {
            Log.w(TAG, "Cannot send text: WebSocket is null")
            return false
        }

        if (!isConnected) {
            Log.w(TAG, "Cannot send text: not connected (state=$state)")
            return false
        }

        return ws.send(text)
    }

    override fun send(bytes: ByteArray): Boolean {
        val ws = webSocket ?: run {
            Log.w(TAG, "Cannot send bytes: WebSocket is null")
            return false
        }

        if (!isConnected) {
            Log.w(TAG, "Cannot send bytes: not connected (state=$state)")
            return false
        }

        return ws.send(ByteString.of(*bytes))
    }

    override fun close(code: Int, reason: String) {
        Log.d(TAG, "Closing WebSocket: code=$code reason=$reason")
        webSocket?.close(code, reason)
        webSocket = null
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
        _state.set(TransportState.Closed)
    }

    /**
     * Check if an error is likely temporary (network glitch) vs. permanent (config error).
     */
    private fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""

        return when {
            // Network errors that might resolve themselves
            cause is SocketException -> true
            cause is java.io.EOFException -> true
            cause is SocketTimeoutException -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true

            // Configuration errors that won't fix themselves
            cause is UnknownHostException -> false
            cause is SSLHandshakeException -> false
            cause is ConnectException -> false
            cause is NoRouteToHostException -> false
            message.contains("refused") -> false

            // Default to recoverable (optimistic)
            else -> true
        }
    }

    private inner class WebSocketEventListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            _state.set(TransportState.Connected)
            listener?.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener?.onMessage(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            listener?.onClosing(code, reason)
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            _state.set(TransportState.Closed)
            this@WebSocketTransport.webSocket = null
            listener?.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            _state.set(TransportState.Failed)
            this@WebSocketTransport.webSocket = null
            listener?.onFailure(t, isRecoverableError(t))
        }
    }
}
