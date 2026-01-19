package com.sendspindroid.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Manages mDNS service advertisement for server-initiated connections.
 *
 * In "Stay Connected" mode, the client advertises itself as a `_sendspin._tcp` service
 * so that SendSpin servers can discover and connect to it to initiate playback remotely.
 *
 * Protocol flow (server-initiated):
 * 1. Client registers mDNS service `_sendspin._tcp.local.` on port 8928
 * 2. Client runs a WebSocket server listening on that port
 * 3. Server discovers clients via mDNS and initiates WebSocket connection
 * 4. Client sends `client/hello` first, server responds with `server/hello`
 * 5. Protocol proceeds normally after handshake
 *
 * Service type: _sendspin._tcp (different from server discovery's _sendspin-server._tcp)
 */
class NsdAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "NsdAdvertiser"

        // SendSpin client mDNS service type (for server-initiated connections)
        // Different from _sendspin-server._tcp used for discovering servers
        private const val SERVICE_TYPE = "_sendspin._tcp"

        // Default port for client WebSocket server
        const val DEFAULT_PORT = 8928

        // WebSocket path (advertised in TXT record)
        private const val WS_PATH = "/sendspin"
    }

    /**
     * Callback interface for advertisement events.
     */
    interface AdvertiserListener {
        fun onServiceRegistered(serviceName: String, port: Int)
        fun onServiceUnregistered()
        fun onRegistrationFailed(errorCode: Int, errorMessage: String)
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isAdvertising = false
    private var registeredServiceName: String? = null
    private var registeredPort: Int = 0
    private var listener: AdvertiserListener? = null

    /**
     * Sets the listener for advertisement events.
     */
    fun setListener(listener: AdvertiserListener?) {
        this.listener = listener
    }

    /**
     * Starts advertising the client as a SendSpin player.
     *
     * @param playerName The name to advertise (shown to servers)
     * @param port The port the WebSocket server is listening on
     */
    fun startAdvertising(playerName: String, port: Int = DEFAULT_PORT) {
        if (isAdvertising) {
            Log.w(TAG, "Already advertising")
            return
        }

        // Acquire multicast lock (required for mDNS)
        acquireMulticastLock()

        // Initialize NsdManager
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        // Create service info
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = playerName
            serviceType = SERVICE_TYPE
            setPort(port)
            // Add TXT records with WebSocket path and version
            setAttribute("path", WS_PATH)
            setAttribute("version", "1")
        }

        // Create registration listener
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Service name might have been changed by NsdManager to avoid conflicts
                registeredServiceName = serviceInfo.serviceName
                registeredPort = port
                isAdvertising = true
                Log.i(TAG, "Service registered: ${serviceInfo.serviceName} on port $port")
                listener?.onServiceRegistered(serviceInfo.serviceName, port)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val errorMsg = nsdErrorToString(errorCode)
                Log.e(TAG, "Registration failed: $errorMsg (code: $errorCode)")
                isAdvertising = false
                releaseMulticastLock()
                listener?.onRegistrationFailed(errorCode, errorMsg)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                isAdvertising = false
                registeredServiceName = null
                registeredPort = 0
                listener?.onServiceUnregistered()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val errorMsg = nsdErrorToString(errorCode)
                Log.e(TAG, "Unregistration failed: $errorMsg (code: $errorCode)")
            }
        }

        // Register the service
        Log.d(TAG, "Registering service: $playerName on port $port")
        try {
            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            releaseMulticastLock()
            listener?.onRegistrationFailed(-1, "Exception: ${e.message}")
        }
    }

    /**
     * Stops advertising the client.
     */
    fun stopAdvertising() {
        if (!isAdvertising) {
            Log.d(TAG, "Not advertising")
            return
        }

        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }

        releaseMulticastLock()
        isAdvertising = false
    }

    /**
     * Re-registers the service (e.g., after network change).
     * Call this when the network changes to refresh mDNS advertisement.
     */
    fun refresh(playerName: String, port: Int = DEFAULT_PORT) {
        if (isAdvertising) {
            Log.d(TAG, "Refreshing advertisement")
            stopAdvertising()
            // Small delay to ensure clean unregistration
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startAdvertising(playerName, port)
            }, 500)
        }
    }

    /**
     * Acquires multicast lock for mDNS advertisement.
     */
    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SendSpinDroid_NsdAdvertiser").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Multicast lock released")
            }
            multicastLock = null
        }
    }

    /**
     * Converts NSD error codes to human-readable strings.
     */
    private fun nsdErrorToString(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "Already active"
        NsdManager.FAILURE_INTERNAL_ERROR -> "Internal error"
        NsdManager.FAILURE_MAX_LIMIT -> "Max limit reached"
        else -> "Unknown error"
    }

    /**
     * Returns whether the service is currently being advertised.
     */
    fun isAdvertising(): Boolean = isAdvertising

    /**
     * Returns the registered service name (may differ from requested due to conflicts).
     */
    fun getRegisteredServiceName(): String? = registeredServiceName

    /**
     * Returns the port the service is advertised on.
     */
    fun getRegisteredPort(): Int = registeredPort

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stopAdvertising()
        nsdManager = null
        registrationListener = null
    }
}
