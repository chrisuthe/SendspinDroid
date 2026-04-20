package com.sendspindroid.network

/**
 * Builds syntactically valid WebSocket URLs from user-entered addresses.
 * Handles IPv6 literal bracket-wrapping per RFC 3986.
 *
 * ## Address forms accepted by [build]
 * - `host` or `host:port` where host is a hostname or IPv4 literal
 * - `[ipv6]` or `[ipv6]:port` - bracketed IPv6 with optional port
 * - bare IPv6 literal (2+ colons, no brackets) - treated as no port, wrapped
 *
 * ## Ambiguity convention
 * A bare string with 2+ colons is treated as an IPv6 literal with no port.
 * To combine an IPv6 literal with a port, the caller must bracket-wrap:
 * `[2001:db8::1]:8927`. This matches RFC 3986 authority syntax.
 */
object WebSocketUrlBuilder {

    /**
     * Build a WebSocket URL from a user-facing address plus path.
     *
     * @param address host or host:port, with IPv6 literals optionally in brackets
     * @param path request path (leading slash added if missing; empty path produces no trailing slash)
     * @param scheme URL scheme (default "ws"; use "wss" for TLS)
     */
    fun build(address: String, path: String, scheme: String = "ws"): String {
        val authority = formatAuthority(address)
        val pathPart = normalizePath(path)
        return "$scheme://$authority$pathPart"
    }

    /**
     * Build a WebSocket URL when host and port are already separated.
     * Wraps IPv6 literals in brackets; passes hostnames and IPv4 through unchanged.
     */
    fun buildFromHostPort(host: String, port: Int, path: String, scheme: String = "ws"): String {
        val hostPart = wrapIfIpv6Literal(stripBrackets(host))
        val pathPart = normalizePath(path)
        return "$scheme://$hostPart:$port$pathPart"
    }

    /**
     * Normalize an address string into an RFC 3986 authority component
     * (i.e. host or host:port, with IPv6 literals bracket-wrapped).
     */
    private fun formatAuthority(address: String): String {
        val trimmed = address.trim()

        // Already bracketed? Keep as-is.
        if (trimmed.startsWith("[")) {
            return trimmed
        }

        val colonCount = trimmed.count { it == ':' }
        return when {
            // No colons: bare hostname or IPv4, no port
            colonCount == 0 -> trimmed

            // Exactly one colon: host:port (hostname or IPv4 with port)
            colonCount == 1 -> trimmed

            // 2+ colons and no brackets: bare IPv6 literal, no port
            // Wrap the whole thing in brackets
            else -> "[$trimmed]"
        }
    }

    private fun wrapIfIpv6Literal(host: String): String {
        return if (host.contains(':')) "[$host]" else host
    }

    private fun stripBrackets(host: String): String {
        return if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
    }

    private fun normalizePath(path: String): String {
        return when {
            path.isEmpty() -> ""
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }
}
