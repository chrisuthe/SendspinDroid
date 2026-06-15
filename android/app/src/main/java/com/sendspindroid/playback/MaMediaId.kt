package com.sendspindroid.playback

/**
 * Encoding/decoding of provider-qualified Music Assistant media IDs used in the
 * Android Auto browse tree, in the form `<id>~<provider>` (the id part here has
 * already had its `ma_album_`/`ma_artist_`/... prefix removed).
 *
 * Decoding splits from the RIGHT: the provider domain is appended last and never
 * contains `~`, whereas a Music Assistant item id occasionally can (e.g. provider
 * URIs encoded as ids). Splitting from the left would corrupt such ids and send
 * the wrong `provider_instance_id_or_domain` to the server.
 */
internal object MaMediaId {
    private const val SEP = "~"
    const val DEFAULT_PROVIDER = "library"

    /** Append the provider to an id: `encode("a1", "spotify")` -> `"a1~spotify"`. */
    fun encode(id: String, provider: String): String = "$id$SEP$provider"

    /**
     * Split an encoded id-part (prefix already removed) into `(id, provider)`.
     * Falls back to [DEFAULT_PROVIDER] when no provider suffix is present or the
     * suffix is empty.
     */
    fun decode(encoded: String): Pair<String, String> {
        if (!encoded.contains(SEP)) return encoded to DEFAULT_PROVIDER
        val id = encoded.substringBeforeLast(SEP)
        val provider = encoded.substringAfterLast(SEP).ifEmpty { DEFAULT_PROVIDER }
        return id to provider
    }
}
