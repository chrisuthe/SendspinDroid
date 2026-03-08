package com.sendspindroid.e2e

import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake SendSpinTransport for E2E tests.
 *
 * Records all sent messages and provides methods to simulate incoming
 * messages and connection events. Allows tests to drive the full
 * connection lifecycle without real network I/O.
 */
class FakeTransport : SendSpinTransport {

    private var _state: TransportState = TransportState.Disconnected
    override val state: TransportState get() = _state
    override val isConnected: Boolean get() = _state == TransportState.Connected

    private var listener: SendSpinTransport.Listener? = null

    /** All text messages sent by the client through this transport. */
    val sentTextMessages = CopyOnWriteArrayList<String>()

    /** All binary messages sent by the client through this transport. */
    val sentBinaryMessages = CopyOnWriteArrayList<ByteArray>()

    /** Whether close() was called. */
    var closed = false
        private set

    /** Whether destroy() was called. */
    var destroyed = false
        private set

    /** Close code passed to close(). */
    var closeCode: Int = -1
        private set

    /** Close reason passed to close(). */
    var closeReason: String = ""
        private set

    override fun connect() {
        _state = TransportState.Connecting
    }

    override fun send(text: String): Boolean {
        if (_state != TransportState.Connected) return false
        sentTextMessages.add(text)
        return true
    }

    override fun send(bytes: ByteArray): Boolean {
        if (_state != TransportState.Connected) return false
        sentBinaryMessages.add(bytes)
        return true
    }

    override fun close(code: Int, reason: String) {
        closed = true
        closeCode = code
        closeReason = reason
        _state = TransportState.Closed
    }

    override fun destroy() {
        destroyed = true
        _state = TransportState.Closed
    }

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    // ========== Simulation Methods ==========

    /**
     * Simulate the transport becoming connected (onConnected callback).
     */
    fun simulateConnected() {
        _state = TransportState.Connected
        listener?.onConnected()
    }

    /**
     * Simulate receiving a text message from the server.
     */
    fun simulateTextMessage(text: String) {
        listener?.onMessage(text)
    }

    /**
     * Simulate receiving a binary message from the server.
     */
    fun simulateBinaryMessage(bytes: ByteArray) {
        listener?.onMessage(bytes)
    }

    /**
     * Simulate the transport closing.
     */
    fun simulateClosed(code: Int = 1000, reason: String = "") {
        _state = TransportState.Closed
        listener?.onClosed(code, reason)
    }

    /**
     * Simulate a transport failure.
     */
    fun simulateFailure(error: Throwable, isRecoverable: Boolean = true) {
        _state = TransportState.Failed
        listener?.onFailure(error, isRecoverable)
    }

    /**
     * Get the current listener (for verification in tests).
     */
    fun getListener(): SendSpinTransport.Listener? = listener

    /**
     * Find sent text messages matching a predicate.
     */
    fun findSentMessages(predicate: (String) -> Boolean): List<String> {
        return sentTextMessages.filter(predicate)
    }

    /**
     * Check if any sent message contains the given substring.
     */
    fun hasSentMessageContaining(substring: String): Boolean {
        return sentTextMessages.any { it.contains(substring) }
    }

    /**
     * Clear recorded messages.
     */
    fun clearRecordedMessages() {
        sentTextMessages.clear()
        sentBinaryMessages.clear()
    }
}
