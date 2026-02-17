package com.sendspindroid.musicassistant.transport

import com.sendspindroid.shared.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
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
 * All map accesses are guarded by [lock]. Methods may be called from any
 * thread (WebSocket callback threads, WebRTC internal threads, coroutine
 * dispatchers). Uses synchronized for KMP compatibility instead of
 * java.util.concurrent.ConcurrentHashMap.
 */
class MaCommandMultiplexer {

    companion object {
        private const val TAG = "MaCommandMultiplexer"
    }

    // Lock object for synchronizing all map access
    private val lock = Any()

    // Pending API commands: message_id -> CompletableDeferred<JsonObject>
    private val pendingCommands = HashMap<String, CompletableDeferred<JsonObject>>()

    // Accumulated partial results: message_id -> list of items received so far
    // MA server sends large results in 500-item batches with "partial": true
    private val partialResults = HashMap<String, MutableList<JsonElement>>()

    // Pending HTTP proxy requests: request_id -> CompletableDeferred<HttpProxyResponse>
    private val pendingProxyRequests =
        HashMap<String, CompletableDeferred<MaApiTransport.HttpProxyResponse>>()

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
        synchronized(lock) {
            pendingCommands[messageId] = deferred
        }
        return messageId to deferred
    }

    /**
     * Register a pending HTTP proxy request.
     *
     * @return Pair of (request_id to include in outgoing message, deferred for the response)
     */
    @OptIn(ExperimentalUuidApi::class)
    fun registerProxyRequest(): Pair<String, CompletableDeferred<MaApiTransport.HttpProxyResponse>> {
        val requestId = "req_${Uuid.random().toString().take(8)}_${Random.nextInt(10000)}"
        val deferred = CompletableDeferred<MaApiTransport.HttpProxyResponse>()
        synchronized(lock) {
            pendingProxyRequests[requestId] = deferred
        }
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

                // Handle partial result: check pending + accumulate in one atomic block
                // to prevent orphaned partialResults entries if unregisterCommand() runs
                // between the check and accumulation.
                if (isPartial) {
                    val resultArray = json.optJsonArray("result")
                    val accumulated = synchronized(lock) {
                        if (pendingCommands.containsKey(messageId) && resultArray != null && resultArray.size > 0) {
                            val list = partialResults.getOrPut(messageId) { mutableListOf() }
                            list.addAll(resultArray)
                            list
                        } else {
                            null
                        }
                    }
                    if (accumulated != null) {
                        Log.d(TAG, "Partial result for $messageId: +${resultArray!!.size} items (total so far: ${accumulated.size})")
                        return
                    }
                    // If no pending command matched, fall through to event listener
                }

                // Final result or error: remove command + partials atomically
                val (deferred, accumulated) = synchronized(lock) {
                    val d = pendingCommands.remove(messageId)
                    val a = partialResults.remove(messageId)
                    d to a
                }
                if (deferred != null) {
                    if (json.has("error_code")) {
                        val errorCode = json.optString("error_code", "unknown")
                        val details = json.optString("details", "Command failed")
                        Log.w(TAG, "Command error: $errorCode - $details")
                        deferred.completeExceptionally(
                            MaApiTransport.MaCommandException(errorCode, details)
                        )
                    } else {
                        // Merge any accumulated partial results with this final batch
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
        val deferred = synchronized(lock) { pendingProxyRequests.remove(requestId) }

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
        val error = MaTransportException("Transport disconnected: $reason")

        synchronized(lock) {
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
    }

    /**
     * Unregister a pending command without completing it.
     *
     * Called by transports when a command times out, so the CompletableDeferred
     * does not leak in [pendingCommands] indefinitely.
     *
     * @param messageId The message ID returned by [registerCommand]
     * @return true if the command was found and removed, false if already completed/removed
     */
    fun unregisterCommand(messageId: String): Boolean {
        val removed = synchronized(lock) {
            val r = pendingCommands.remove(messageId) != null
            partialResults.remove(messageId)
            r
        }
        if (removed) {
            Log.d(TAG, "Unregistered timed-out command: $messageId")
        }
        return removed
    }

    /**
     * Unregister a pending HTTP proxy request without completing it.
     *
     * Called by transports when a proxy request times out, so the
     * CompletableDeferred does not leak in [pendingProxyRequests] indefinitely.
     *
     * @param requestId The request ID returned by [registerProxyRequest]
     * @return true if the request was found and removed, false if already completed/removed
     */
    fun unregisterProxyRequest(requestId: String): Boolean {
        val removed = synchronized(lock) { pendingProxyRequests.remove(requestId) != null }
        if (removed) {
            Log.d(TAG, "Unregistered timed-out proxy request: $requestId")
        }
        return removed
    }

    /**
     * Number of pending commands (for diagnostics).
     */
    val pendingCommandCount: Int
        get() = synchronized(lock) { pendingCommands.size }

    /**
     * Number of pending proxy requests (for diagnostics).
     */
    val pendingProxyRequestCount: Int
        get() = synchronized(lock) { pendingProxyRequests.size }

    /**
     * Decode a hex-encoded string to bytes.
     *
     * The MA server gateway encodes HTTP proxy response bodies as hex strings
     * using Python's bytes.hex() method.
     *
     * @throws IllegalArgumentException if [hex] has an odd number of characters
     */
    internal fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        require(hex.length % 2 == 0) {
            "Hex string must have an even number of characters, got ${hex.length}"
        }

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
    internal fun hexCharToInt(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> 0
    }
}
