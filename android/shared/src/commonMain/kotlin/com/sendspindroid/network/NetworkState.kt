package com.sendspindroid.network

import com.sendspindroid.shared.platform.Platform

data class NetworkState(
    val transportType: TransportType = TransportType.UNKNOWN,
    val isMetered: Boolean = true,
    val isConnected: Boolean = false,
    val downstreamBandwidthKbps: Int? = null,
    val upstreamBandwidthKbps: Int? = null,
    val wifiRssi: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val wifiFrequencyMhz: Int? = null,
    val cellularType: CellularType? = null,
    val quality: NetworkQuality = NetworkQuality.UNKNOWN,
    val timestampMs: Long = Platform.currentTimeMillis()
) {
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

enum class TransportType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    UNKNOWN
}

enum class CellularType {
    TYPE_2G,
    TYPE_3G,
    TYPE_LTE,
    TYPE_5G,
    UNKNOWN
}

enum class NetworkQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNKNOWN
}
