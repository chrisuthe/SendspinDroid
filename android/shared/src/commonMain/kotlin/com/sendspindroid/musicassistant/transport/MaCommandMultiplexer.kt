package com.sendspindroid.musicassistant.transport

import com.sendspindroid.shared.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Command multiplexer for the Music Assistant API protocol.
 *
 * Handles message_id-based request/response correlation for both
 * WebSocket and DataChannel transports. Also routes HTTP proxy
 * responses and server-push events.
 *
 * ## Message Routing
 * ```
 * Incoming message
 *      |
 *      +-- has message_id matching pending command? --> complete the deferred
 *      |
 *      +-- has type "http-proxy-response"? --> route to pending proxy request
 *      |
 *      +-- otherwise --> forward to event listener
 * ```
 *
 * ## Thread Safety
 * All collections are concurrent. Methods may be called from any thread
 * (WebSocket callback threads, WebRTC internal threads, coroutine dispatchers).
 */
class MaCommandMultiplexer {

    companion object {
        private const val TAG = "MaCommandMultiplexer"
    }

    // Pending API commands: message_id -> CompletableDeferred<JsonObject>
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    // Accumulated partial results: message_id -> list of items received so far
    // MA server sends large results in 500-item batches with "partial": true
    private val partialResults = ConcurrentHashMap<String, MutableList<JsonElement>>()

    // Pending HTTP proxy requests: request_id -> CompletableDeferred<HttpProxyResponse>
    private val pendingProxyRequests =
        ConcurrentHashMap<String, CompletableDeferred<MaApiTransport.HttpProxyResponse>>()

    // Event listener for server-push messages
    @Volatile
    var eventListener: MaApiTransport.EventListener? = null

    /**
     * Register a pending command.
     *
     * @return Pair of (message_id to include in outgoing JSON, deferred for the response)
     */
    @OptIn(ExperimentalUuidApi::class)
    fun registerCommand(): Pair<String, CompletableDeferred<JsonObject>> {
        val messageId = Uuid.random().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pendingCommands[messageId] = deferred
        return messageId to deferred
    }

    /**
     * Register a pending HTTP proxy request.
     *
     * @return Pair of (request_id to include in outgoing message, deferred for the response)
     */
    fun registerProxyRequest(): Pair<String, CompletableDeferred<MaApiTransport.HttpProxyResponse>> {
        val requestId = "req_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val deferred = CompletableDeferred<MaApiTransport.HttpProxyResponse>()
        pendingProxyRequests[requestId] = deferred
        return requestId to deferred
    }

    /**
     * Handle an incoming message from the transport.
     *
     * Routes to the appropriate pending command, proxy request, or event listener.
     *
     * @param text The raw JSON string received from the transport
     */
    fun onMessage(text: String) {
        try {
            val json = Json.parseToJsonElement(text).jsonObject

            // Check for HTTP proxy response
            val type = json.optString("type")
            if (type == "http-proxy-response") {
                val status = json.optInt("status")
                val bodyHex = json.optString("body")
                val bodyPreview = if (status >= 400 && bodyHex.isNotEmpty()) {
                    try { String(hexToBytes(bodyHex), Charsets.UTF_8).take(200) } catch (_: Exception) { "?" }
                } else { "(${bodyHex.length / 2} bytes)" }
                Log.d(TAG, "HTTP proxy response: id=${json.optString("id")}, status=$status, body=$bodyPreview")
                handleProxyResponse(json)
                return
            }

            // Check for command response (has message_id matching a pending command)
            val messageId = json.optString("message_id")
            if (messageId.isNotEmpty()) {
                val isPartial = json.optBoolean("partial")

                // Handle partial result: accumulate and wait for more
                if (isPartial && pendingCommands.containsKey(messageId)) {
                    val resultArray = json.optJsonArray("result")
                    if (resultArray != null && resultArray.size > 0) {
                        val accumulated = partialResults.getOrPut(messageId) { mutableListOf() }
                        accumulated.addAll(resultArray)
                        Log.d(TAG, "Partial result for $messageId: +${resultArray.size} items (total so far: ${accumulated.size})")
                    }
                    return
                }

                val deferred = pendingCommands.remove(messageId)
                if (deferred != null) {
                    if (json.has("error_code")) {
                        val errorCode = json.optString("error_code", "unknown")
                        val details = json.optString("details", "Command failed")
                        Log.w(TAG, "Command error: $errorCode - $details")
                        partialResults.remove(messageId)
                        deferred.completeExceptionally(
                            MaApiTransport.MaCommandException(errorCode, details)
                        )
                    } else {
                        // Merge any accumulated partial results with this final batch
                        val accumulated = partialResults.remove(messageId)
                        val finalJson = if (accumulated != null) {
                            val finalArray = json.optJsonArray("result")
                            if (finalArray != null) {
                                accumulated.addAll(finalArray)
                            }
                            val mergedArray = JsonArray(accumulated)
                            // Rebuild the json object with merged result
                            buildJsonObject {
                                json.forEach { (k, v) ->
                                    if (k != "result") put(k, v)
                                }
                                put("result", mergedArray)
                            }
                        } else {
                            json
                        }

                        if (accumulated != null) {
                            Log.d(TAG, "Merged partial results for $messageId: ${accumulated.size} total items")
                        }
                        deferred.complete(finalJson)
                    }
                    return
                }
                // message_id present but no matching pending command - might be
                // an event or a response to something we already timed out on
                // Also clean up any stale partial results
                partialResults.remove(messageId)
            }

            // Server-push event (no matching message_id)
            eventListener?.onEvent(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    /**
     * Handle an HTTP proxy response.
     */
    private fun handleProxyResponse(json: JsonObject) {
        val requestId = json.optString("id")
        val deferred = pendingProxyRequests.remove(requestId)

        if (deferred == null) {
            Log.w(TAG, "Received proxy response for unknown request: $requestId")
            return
        }

        try {
            val status = json.optInt("status", 500)
            val headersJson = json.optJsonObject("headers")
            val headers = mutableMapOf<String, String>()
            headersJson?.forEach { (key, value) ->
                headers[key] = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            }

            // Body is hex-encoded
            val hexBody = json.optString("body")
            val body = hexToBytes(hexBody)

            deferred.complete(MaApiTransport.HttpProxyResponse(status, headers, body))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing proxy response", e)
            deferred.completeExceptionally(e)
        }
    }

    /**
     * Cancel all pending commands and proxy requests.
     *
     * Called on disconnect or transport error.
     *
     * @param reason Human-readable reason for the cancellation
     */
    fun cancelAll(reason: String) {
        val error = IOException("Transport disconnected: $reason")

        pendingCommands.forEach { (_, deferred) ->
            deferred.completeExceptionally(error)
        }
        pendingCommands.clear()
        partialResults.clear()

        pendingProxyRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(error)
        }
        pendingProxyRequests.clear()
    }

    /**
     * Number of pending commands (for diagnostics).
     */
    val pendingCommandCount: Int
        get() = pendingCommands.size

    /**
     * Number of pending proxy requests (for diagnostics).
     */
    val pendingProxyRequestCount: Int
        get() = pendingProxyRequests.size

    /**
     * Decode a hex-encoded string to bytes.
     *
     * The MA server gateway encodes HTTP proxy response bodies as hex strings
     * using Python's bytes.hex() method.
     */
    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)

        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = hexCharToInt(hex[i])
            val low = hexCharToInt(hex[i + 1])
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    /** Convert a hex character to its int value (0-15). */
    private fun hexCharToInt(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> 0
    }
}
