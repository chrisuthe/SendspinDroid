package com.sendspindroid.remote

import android.content.Context
import android.util.Log
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * WebRTC-based transport for remote connections via Music Assistant.
 *
 * This transport establishes a WebRTC connection through the MA signaling server
 * and uses a DataChannel named "sendspin" to carry the SendSpin protocol.
 *
 * ## Connection Flow
 * 1. Connect to signaling server with Remote ID
 * 2. Receive ICE servers from MA server
 * 3. Create PeerConnection with ICE configuration
 * 4. Create DataChannel "sendspin"
 * 5. Generate SDP offer and send via signaling
 * 6. Receive SDP answer and set remote description
 * 7. Exchange ICE candidates until connection established
 * 8. DataChannel opens - transport is ready
 *
 * ## Threading
 * WebRTC callbacks come on internal WebRTC threads. This class bridges
 * to the IO dispatcher for consistency with the rest of the app.
 *
 * @param context Android context for PeerConnectionFactory initialization
 * @param remoteId The 26-character Remote ID from Music Assistant settings
 */
class WebRTCTransport(
    private val context: Context,
    private val remoteId: String
) : SendSpinTransport, SignalingClient.Listener {

    /**
     * Listener for the MA API DataChannel lifecycle.
     * Called when the secondary "ma-api" channel opens or closes.
     */
    interface MaApiChannelListener {
        fun onMaApiChannelOpen(channel: DataChannel)
        fun onMaApiChannelClosed()
    }

    companion object {
        private const val TAG = "WebRTCTransport"
        private const val DATA_CHANNEL_NAME = "sendspin"
        private const val MA_API_CHANNEL_NAME = "ma-api"

        // Singleton initialization flag
        @Volatile
        private var factoryInitialized = false

        private var peerConnectionFactory: PeerConnectionFactory? = null
        private var eglBase: EglBase? = null

        /**
         * Initialize WebRTC factory. Must be called once per app lifecycle.
         * Thread-safe and idempotent.
         */
        @Synchronized
        fun initializeFactory(context: Context) {
            if (factoryInitialized) return

            Log.d(TAG, "Initializing PeerConnectionFactory")

            // Initialize PeerConnectionFactory globals
            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            // Create EglBase for hardware acceleration (optional for data-only)
            eglBase = EglBase.create()

            // Create PeerConnectionFactory
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .createPeerConnectionFactory()

            factoryInitialized = true
            Log.d(TAG, "PeerConnectionFactory initialized")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.get()

    private var signalingClient: SignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var maApiDataChannel: DataChannel? = null
    private var listener: SendSpinTransport.Listener? = null
    private var maApiChannelListener: MaApiChannelListener? = null

    // Queue ICE candidates until remote description is set
    private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
    private var remoteDescriptionSet = false

    // Buffer for MA API DataChannel messages received before MaDataChannelTransport
    // takes over. The MA gateway sends ServerInfo immediately when the channel opens,
    // but PlaybackService may not have wired up the transport yet.
    private val maApiMessageBuffer = ConcurrentLinkedQueue<String>()

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    /**
     * Set a listener for the MA API DataChannel lifecycle.
     * The listener is notified when the "ma-api" channel opens or closes.
     */
    fun setMaApiChannelListener(listener: MaApiChannelListener?) {
        this.maApiChannelListener = listener
    }

    /**
     * Get the MA API DataChannel, if open.
     * Returns null if not connected or channel not yet open.
     */
    fun getMaApiDataChannel(): DataChannel? {
        val dc = maApiDataChannel
        return if (dc?.state() == DataChannel.State.OPEN) dc else null
    }

    /**
     * Drain any buffered MA API messages received before the transport was wired up.
     *
     * The MA gateway sends ServerInfo immediately when the DataChannel is bridged.
     * This message may arrive before [MaDataChannelTransport] registers its observer.
     * Call this after creating the transport to replay any early messages.
     *
     * @return List of buffered text messages (may be empty)
     */
    fun drainMaApiMessageBuffer(): List<String> {
        val messages = mutableListOf<String>()
        while (true) {
            val msg = maApiMessageBuffer.poll() ?: break
            messages.add(msg)
        }
        if (messages.isNotEmpty()) {
            Log.d(TAG, "Drained ${messages.size} buffered MA API message(s)")
        }
        return messages
    }

    override fun connect() {
        if (!_state.compareAndSet(TransportState.Disconnected, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Failed, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Closed, TransportState.Connecting)) {
            Log.w(TAG, "Cannot connect: already $state")
            return
        }

        Log.i(TAG, "Starting remote connection to: $remoteId")

        // Ensure factory is initialized
        initializeFactory(context)

        // Start signaling connection
        signalingClient = SignalingClient(remoteId).apply {
            setListener(this@WebRTCTransport)
            connect()
        }
    }

    override fun send(text: String): Boolean {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "Cannot send text: DataChannel not open")
            return false
        }

        val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
        val dataBuffer = DataChannel.Buffer(buffer, false) // false = text
        return dc.send(dataBuffer)
    }

    override fun send(bytes: ByteArray): Boolean {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "Cannot send bytes: DataChannel not open")
            return false
        }

        val buffer = ByteBuffer.wrap(bytes)
        val dataBuffer = DataChannel.Buffer(buffer, true) // true = binary
        return dc.send(dataBuffer)
    }

    override fun close(code: Int, reason: String) {
        Log.d(TAG, "Closing WebRTC transport: code=$code reason=$reason")
        cleanup()
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
        _state.set(TransportState.Closed)
    }

    private fun cleanup() {
        dataChannel?.close()
        dataChannel = null

        maApiDataChannel?.close()
        maApiDataChannel = null

        peerConnection?.close()
        peerConnection = null

        signalingClient?.disconnect()
        signalingClient = null

        pendingIceCandidates.clear()
        maApiMessageBuffer.clear()
        remoteDescriptionSet = false
    }

    // ========== SignalingClient.Listener ==========

    override fun onServerConnected(iceServers: List<IceServerConfig>) {
        Log.i(TAG, "MA server connected, creating PeerConnection with ${iceServers.size} ICE servers")

        scope.launch {
            createPeerConnection(iceServers)
        }
    }

    override fun onAnswer(sdp: String) {
        Log.d(TAG, "Setting remote description (answer)")

        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}

            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                remoteDescriptionSet = true

                // Add any queued ICE candidates
                while (pendingIceCandidates.isNotEmpty()) {
                    val candidate = pendingIceCandidates.poll()
                    if (candidate != null) {
                        Log.d(TAG, "Adding queued ICE candidate")
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                handleError("Failed to set remote description: $error")
            }
        }, sessionDescription)
    }

    override fun onIceCandidate(candidate: IceCandidateInfo) {
        val iceCandidate = IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.sdp
        )

        if (remoteDescriptionSet) {
            Log.d(TAG, "Adding ICE candidate")
            peerConnection?.addIceCandidate(iceCandidate)
        } else {
            Log.d(TAG, "Queuing ICE candidate (remote description not set yet)")
            pendingIceCandidates.add(iceCandidate)
        }
    }

    override fun onError(message: String) {
        handleError(message)
    }

    override fun onDisconnected() {
        Log.d(TAG, "Signaling disconnected")
        // Only treat as error if we're not already connected via DataChannel
        if (state != TransportState.Connected) {
            handleError("Signaling connection lost")
        }
    }

    // ========== Private Methods ==========

    private fun createPeerConnection(iceServers: List<IceServerConfig>) {
        val factory = peerConnectionFactory ?: run {
            handleError("PeerConnectionFactory not initialized")
            return
        }

        // Build ICE server list for WebRTC
        val rtcIceServers = iceServers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.url)
            if (config.username != null) {
                builder.setUsername(config.username)
            }
            if (config.credential != null) {
                builder.setPassword(config.credential)
            }
            builder.createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            // Allow both STUN and TURN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Enable ICE candidate trickling
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, PeerConnectionObserver())

        if (peerConnection == null) {
            handleError("Failed to create PeerConnection")
            return
        }

        // Create DataChannel for SendSpin protocol (audio)
        val dcInit = DataChannel.Init().apply {
            ordered = true  // Maintain message order
            maxRetransmits = -1  // Reliable delivery
        }
        dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_NAME, dcInit)
        dataChannel?.registerObserver(DataChannelObserver())
        Log.d(TAG, "DataChannel created: $DATA_CHANNEL_NAME")

        // Create DataChannel for MA API (text-only, ordered, reliable)
        val maApiInit = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1  // Reliable delivery
        }
        maApiDataChannel = peerConnection?.createDataChannel(MA_API_CHANNEL_NAME, maApiInit)
        maApiDataChannel?.registerObserver(MaApiDataChannelObserver())
        Log.d(TAG, "DataChannel created: $MA_API_CHANNEL_NAME")

        // Create and send SDP offer
        createOffer()
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "SDP offer created")
                peerConnection?.setLocalDescription(LocalDescriptionObserver(), sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
                handleError("Failed to create offer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun handleError(message: String) {
        Log.e(TAG, "WebRTC error: $message")
        _state.set(TransportState.Failed)
        cleanup()
        listener?.onFailure(Exception(message), false)
    }

    // ========== WebRTC Observers ==========

    private inner class PeerConnectionObserver : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(50)}...")
            signalingClient?.sendIceCandidate(
                IceCandidateInfo(
                    sdp = candidate.sdp,
                    sdpMid = candidate.sdpMid ?: "",
                    sdpMLineIndex = candidate.sdpMLineIndex
                )
            )
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $newState")
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    Log.i(TAG, "ICE connection established")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    handleError("ICE connection failed")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "ICE connection disconnected")
                    // Don't immediately fail - ICE might reconnect
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    if (state != TransportState.Closed) {
                        _state.set(TransportState.Closed)
                        listener?.onClosed(1000, "ICE connection closed")
                    }
                }
                else -> {}
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "Peer connection state: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED -> {
                    handleError("Peer connection failed")
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    if (state != TransportState.Closed) {
                        _state.set(TransportState.Closed)
                        listener?.onClosed(1000, "Peer connection closed")
                    }
                }
                else -> {}
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Log.d(TAG, "Signaling state: $newState")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering state: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
    }

    private inner class DataChannelObserver : DataChannel.Observer {

        override fun onStateChange() {
            val dc = dataChannel ?: return
            Log.d(TAG, "DataChannel state: ${dc.state()}")

            when (dc.state()) {
                DataChannel.State.OPEN -> {
                    Log.i(TAG, "DataChannel open - transport ready")
                    _state.set(TransportState.Connected)
                    listener?.onConnected()
                }
                DataChannel.State.CLOSED -> {
                    if (state != TransportState.Closed) {
                        _state.set(TransportState.Closed)
                        listener?.onClosed(1000, "DataChannel closed")
                    }
                }
                DataChannel.State.CLOSING -> {
                    listener?.onClosing(1000, "DataChannel closing")
                }
                else -> {}
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)

            if (buffer.binary) {
                listener?.onMessage(bytes)
            } else {
                val text = String(bytes, Charsets.UTF_8)
                listener?.onMessage(text)
            }
        }

        override fun onBufferedAmountChange(previousAmount: Long) {}
    }

    /**
     * Observer for the MA API DataChannel ("ma-api").
     *
     * This observer only handles lifecycle events (open/close). Message handling
     * is delegated to MaDataChannelTransport which registers its own observer
     * after receiving the channel reference.
     */
    private inner class MaApiDataChannelObserver : DataChannel.Observer {

        override fun onStateChange() {
            val dc = maApiDataChannel ?: return
            Log.d(TAG, "MA API DataChannel state: ${dc.state()}")

            when (dc.state()) {
                DataChannel.State.OPEN -> {
                    Log.i(TAG, "MA API DataChannel open")
                    scope.launch {
                        maApiChannelListener?.onMaApiChannelOpen(dc)
                    }
                }
                DataChannel.State.CLOSED -> {
                    Log.d(TAG, "MA API DataChannel closed")
                    scope.launch {
                        maApiChannelListener?.onMaApiChannelClosed()
                    }
                }
                else -> {}
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // Buffer text messages (e.g., ServerInfo) that arrive before
            // MaDataChannelTransport registers its own observer.
            if (!buffer.binary) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                maApiMessageBuffer.add(text)
                Log.d(TAG, "Buffered MA API message (${text.length} chars)")
            }
        }

        override fun onBufferedAmountChange(previousAmount: Long) {}
    }

    private inner class LocalDescriptionObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}

        override fun onSetSuccess() {
            val localSdp = peerConnection?.localDescription
            if (localSdp != null) {
                Log.d(TAG, "Local description set, sending offer to signaling")
                signalingClient?.sendOffer(localSdp.description)
            }
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "Failed to set local description: $error")
            handleError("Failed to set local description: $error")
        }
    }
}
