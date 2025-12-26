package com.sendspindroid

/**
 * Data class representing a discovered or manually added audio server.
 *
 * Best practice: Using data class for automatic equals(), hashCode(), toString(), copy()
 *
 * @property name Human-readable server name (from mDNS or user input)
 * @property address Network address in "host:port" format (e.g., "192.168.1.100:8927")
 * @property path WebSocket path from mDNS TXT records (default: /sendspin)
 *
 * Design note: Kept simple for v1. For v2, consider:
 * - Adding unique ID field for more reliable deduplication
 * - Adding connection status field (DISCOVERED, CONNECTING, CONNECTED, FAILED)
 * - Adding timestamp for "last seen" functionality
 * - Making this a Room entity for persistence across app restarts
 */
data class ServerInfo(
    val name: String,
    val address: String,
    val path: String = "/sendspin"
)
