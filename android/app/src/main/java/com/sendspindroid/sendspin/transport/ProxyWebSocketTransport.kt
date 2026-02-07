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
 * WebSocket transport for authenticated reverse proxy connections.
 *
 * Unlike [WebSocketTransport] which takes a host:port format for local networks,
 * this transport accepts full URLs (with TLS support) for connecting through
 * reverse proxies like Nginx Proxy Manager, Traefik, or Caddy.
 *
 * ## URL Handling
 * - `https://domain.com/path` → converted to `wss://domain.com/path`
 * - `http://domain.com/path` → converted to `ws://domain.com/path`
 * - `wss://` and `ws://` URLs used as-is
 * - URLs without scheme default to `wss://` (secure)
 *
 * ## Security
 * - TLS is enforced by default for remote connections
 * - Use wss:// for encrypted connections through proxies
 *
 * @param url Full URL to connect to (e.g., "https://ma.example.com/sendspin")
 * @param okHttpClient Optional OkHttpClient instance (creates one if not provided)
 */
class ProxyWebSocketTransport(
    private val url: String,
    pingIntervalSeconds: Long = 30,
    private val okHttpClient: OkHttpClient = createDefaultClient(pingIntervalSeconds)
) : SendSpinTransport {

    companion object {
        private const val TAG = "ProxyWebSocketTransport"

        /**
         * Create a default OkHttpClient configured for proxy WebSocket connections.
         *
         * - Short connect timeout (10s) for reasonable failure detection
         * - No read timeout (required for WebSocket)
         * - Configurable ping interval (default 30s, 15s in High Power Mode)
         */
        fun createDefaultClient(pingIntervalSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Required for WebSocket
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
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

        // Convert URL scheme to WebSocket scheme
        val wsUrl = convertToWebSocketUrl(url)
        Log.d(TAG, "Connecting to proxy: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
    }

    /**
     * Convert HTTP/HTTPS URL to WebSocket URL.
     */
    private fun convertToWebSocketUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            // No scheme - assume secure
            else -> "wss://$url"
        }
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

            // Auth failures are not recoverable
            message.contains("401") -> false
            message.contains("403") -> false
            message.contains("unauthorized") -> false

            // Default to recoverable (optimistic)
            else -> true
        }
    }

    private inner class WebSocketEventListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Proxy WebSocket connected")
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
            Log.d(TAG, "Proxy WebSocket closing: $code $reason")
            listener?.onClosing(code, reason)
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Proxy WebSocket closed: $code $reason")
            _state.set(TransportState.Closed)
            this@ProxyWebSocketTransport.webSocket = null
            listener?.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Proxy WebSocket failure", t)
            _state.set(TransportState.Failed)
            this@ProxyWebSocketTransport.webSocket = null
            listener?.onFailure(t, isRecoverableError(t))
        }
    }
}
