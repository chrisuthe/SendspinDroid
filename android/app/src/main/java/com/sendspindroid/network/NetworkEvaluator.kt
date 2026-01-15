package com.sendspindroid.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Passive network evaluation using Android system APIs.
 *
 * Design principles:
 * - No active network probing (respects user bandwidth)
 * - Thread-safe (network callbacks run on system thread)
 * - Logs transitions for debugging
 * - Exposes state via StateFlow for reactive UI
 *
 * ## Thread Safety
 * Network callbacks run on a system binder thread. All state updates
 * use AtomicReference for lock-free thread safety.
 */
class NetworkEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "NetworkEvaluator"

        // WiFi RSSI thresholds (dBm)
        private const val WIFI_RSSI_EXCELLENT = -50
        private const val WIFI_RSSI_GOOD = -65
        private const val WIFI_RSSI_FAIR = -75
        private const val WIFI_RSSI_POOR = -85
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    // Thread-safe state storage
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    // Track previous state for change detection
    private val previousState = AtomicReference<NetworkState?>(null)

    /**
     * Listener for network state changes.
     */
    interface Listener {
        fun onNetworkStateChanged(oldState: NetworkState?, newState: NetworkState)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Updates network state from the current active network.
     * Called from NetworkCallback or on-demand.
     *
     * Thread-safe: Can be called from any thread.
     */
    fun evaluateCurrentNetwork(network: Network? = null) {
        val activeNetwork = network ?: connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        val newState = if (capabilities != null) {
            buildNetworkState(capabilities)
        } else {
            NetworkState(isConnected = false)
        }

        val oldState = previousState.getAndSet(newState)
        _networkState.value = newState

        // Log state changes
        if (hasSignificantChange(oldState, newState)) {
            logNetworkTransition(oldState, newState)
            listener?.onNetworkStateChanged(oldState, newState)
        }
    }

    /**
     * Builds NetworkState from NetworkCapabilities.
     */
    private fun buildNetworkState(capabilities: NetworkCapabilities): NetworkState {
        val transportType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> TransportType.VPN
            else -> TransportType.UNKNOWN
        }

        val downBandwidth = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 }
        val upBandwidth = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        // Get WiFi details if on WiFi
        val (rssi, linkSpeed, frequency) = if (transportType == TransportType.WIFI) {
            getWifiDetails()
        } else {
            Triple(null, null, null)
        }

        // Get cellular details if on cellular
        val cellType = if (transportType == TransportType.CELLULAR) {
            getCellularType()
        } else {
            null
        }

        val quality = assessQuality(transportType, rssi, cellType, downBandwidth)

        return NetworkState(
            transportType = transportType,
            isMetered = isMetered,
            isConnected = true,
            downstreamBandwidthKbps = downBandwidth,
            upstreamBandwidthKbps = upBandwidth,
            wifiRssi = rssi,
            wifiLinkSpeedMbps = linkSpeed,
            wifiFrequencyMhz = frequency,
            cellularType = cellType,
            quality = quality
        )
    }

    @Suppress("DEPRECATION")
    private fun getWifiDetails(): Triple<Int?, Int?, Int?> {
        val info = wifiManager?.connectionInfo ?: return Triple(null, null, null)
        return Triple(
            info.rssi.takeIf { it != -127 },  // -127 = unavailable
            info.linkSpeed.takeIf { it > 0 },
            info.frequency.takeIf { it > 0 }
        )
    }

    @Suppress("DEPRECATION")
    private fun getCellularType(): CellularType {
        // dataNetworkType requires READ_PHONE_STATE permission on some devices
        // Catch SecurityException and return UNKNOWN rather than crashing
        val networkType = try {
            telephonyManager?.dataNetworkType ?: return CellularType.UNKNOWN
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot get cellular type - permission denied", e)
            return CellularType.UNKNOWN
        }

        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> CellularType.TYPE_2G

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> CellularType.TYPE_3G

            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> CellularType.TYPE_LTE

            TelephonyManager.NETWORK_TYPE_NR -> CellularType.TYPE_5G

            else -> CellularType.UNKNOWN
        }
    }

    private fun assessQuality(
        transport: TransportType,
        rssi: Int?,
        cellType: CellularType?,
        bandwidthKbps: Int?
    ): NetworkQuality {
        return when (transport) {
            TransportType.WIFI -> {
                when {
                    rssi == null -> NetworkQuality.UNKNOWN
                    rssi > WIFI_RSSI_EXCELLENT -> NetworkQuality.EXCELLENT
                    rssi > WIFI_RSSI_GOOD -> NetworkQuality.GOOD
                    rssi > WIFI_RSSI_FAIR -> NetworkQuality.FAIR
                    rssi > WIFI_RSSI_POOR -> NetworkQuality.POOR
                    else -> NetworkQuality.POOR
                }
            }
            TransportType.CELLULAR -> {
                when (cellType) {
                    CellularType.TYPE_5G -> NetworkQuality.EXCELLENT
                    CellularType.TYPE_LTE -> NetworkQuality.GOOD
                    CellularType.TYPE_3G -> NetworkQuality.FAIR
                    CellularType.TYPE_2G -> NetworkQuality.POOR
                    else -> NetworkQuality.UNKNOWN
                }
            }
            TransportType.ETHERNET -> NetworkQuality.EXCELLENT
            TransportType.VPN -> {
                // VPN quality depends on underlying network, estimate from bandwidth
                when {
                    bandwidthKbps == null -> NetworkQuality.UNKNOWN
                    bandwidthKbps > 10_000 -> NetworkQuality.EXCELLENT
                    bandwidthKbps > 5_000 -> NetworkQuality.GOOD
                    bandwidthKbps > 1_000 -> NetworkQuality.FAIR
                    else -> NetworkQuality.POOR
                }
            }
            TransportType.UNKNOWN -> NetworkQuality.UNKNOWN
        }
    }

    private fun hasSignificantChange(old: NetworkState?, new: NetworkState): Boolean {
        if (old == null) return true
        if (old.transportType != new.transportType) return true
        if (old.isConnected != new.isConnected) return true
        if (old.quality != new.quality) return true
        if (old.cellularType != new.cellularType) return true
        // WiFi frequency band change (2.4GHz <-> 5GHz)
        val oldBand = old.wifiFrequencyMhz?.let { if (it > 4900) "5GHz" else "2.4GHz" }
        val newBand = new.wifiFrequencyMhz?.let { if (it > 4900) "5GHz" else "2.4GHz" }
        if (oldBand != newBand && newBand != null) return true
        return false
    }

    private fun logNetworkTransition(old: NetworkState?, new: NetworkState) {
        if (old == null) {
            Log.i(TAG, "Initial network state: ${new.toLogString()}")
        } else if (!new.isConnected) {
            Log.i(TAG, "Network lost: ${old.toLogString()} -> Disconnected")
        } else {
            Log.i(TAG, "Network changed: ${old.toLogString()} -> ${new.toLogString()}")
        }
    }
}
