package com.sendspindroid.network

/**
 * Immutable snapshot of current network conditions.
 * All fields are optional to handle cases where data is unavailable.
 */
data class NetworkState(
    // Basic network info
    val transportType: TransportType = TransportType.UNKNOWN,
    val isMetered: Boolean = true,
    val isConnected: Boolean = false,

    // Bandwidth estimates from NetworkCapabilities (Kbps)
    val downstreamBandwidthKbps: Int? = null,
    val upstreamBandwidthKbps: Int? = null,

    // WiFi-specific (only populated when on WiFi)
    val wifiRssi: Int? = null,              // dBm, typically -30 to -90
    val wifiLinkSpeedMbps: Int? = null,
    val wifiFrequencyMhz: Int? = null,      // 2400 or 5000 range

    // Cellular-specific (only populated when on cellular)
    val cellularType: CellularType? = null,

    // Quality assessment
    val quality: NetworkQuality = NetworkQuality.UNKNOWN,

    // Timestamp for freshness
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * Returns a short description for logging.
     */
    fun toLogString(): String {
        return when (transportType) {
            TransportType.WIFI -> {
                val freq = wifiFrequencyMhz?.let { if (it > 4900) "5GHz" else "2.4GHz" } ?: "?"
                "WiFi(RSSI=$wifiRssi dBm, speed=$wifiLinkSpeedMbps Mbps, $freq, $quality)"
            }
            TransportType.CELLULAR -> "Cellular($cellularType, $quality)"
            TransportType.ETHERNET -> "Ethernet($quality)"
            TransportType.VPN -> "VPN($quality)"
            TransportType.UNKNOWN -> "Unknown"
        }
    }
}

/**
 * Network transport type.
 */
enum class TransportType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    UNKNOWN
}

/**
 * Cellular network generation.
 */
enum class CellularType {
    TYPE_2G,    // GPRS, EDGE, CDMA
    TYPE_3G,    // UMTS, HSPA, EVDO
    TYPE_LTE,   // 4G LTE
    TYPE_5G,    // 5G NR
    UNKNOWN
}

/**
 * Assessed network quality based on signal strength and type.
 */
enum class NetworkQuality {
    EXCELLENT,  // WiFi: RSSI > -50, strong 5G/LTE
    GOOD,       // WiFi: RSSI > -65, normal LTE
    FAIR,       // WiFi: RSSI > -75, weak LTE/3G
    POOR,       // WiFi: RSSI > -85, 2G/weak 3G
    UNKNOWN
}
