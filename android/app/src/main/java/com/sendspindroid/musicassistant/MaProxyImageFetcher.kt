package com.sendspindroid.musicassistant

import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.io.IOException

/**
 * Coil Fetcher for `ma-proxy://` image URIs.
 *
 * In REMOTE mode, images hosted on the MA server (via `/imageproxy`) can't be
 * fetched via direct HTTP because we only have a WebRTC connection. Instead,
 * the image URI uses the `ma-proxy://` scheme:
 *
 * ```
 * ma-proxy:///imageproxy?provider=plex--xxx&size=300&fmt=jpeg&path=%2Flibrary%2F...
 * ```
 *
 * This fetcher strips the scheme to reconstruct the relative path + query, then
 * uses [MaApiTransport.httpProxy][com.sendspindroid.musicassistant.transport.MaApiTransport.httpProxy]
 * to fetch the image bytes over the WebRTC DataChannel via the MA server's HTTP proxy protocol.
 *
 * ## Registration
 * Register this factory in all ImageLoader instances:
 * ```kotlin
 * ImageLoader.Builder(context)
 *     .components {
 *         add(MaProxyImageFetcher.Factory())
 *     }
 *     .build()
 * ```
 */
class MaProxyImageFetcher(
    private val path: String,
    private val options: Options
) : Fetcher {

    companion object {
        private const val TAG = "MaProxyImageFetcher"

        /** URI scheme used for images that must be fetched via DataChannel HTTP proxy. */
        const val SCHEME = "ma-proxy"
    }

    override suspend fun fetch(): FetchResult {
        val transport = MusicAssistantManager.getApiTransport()
            ?: throw IOException("MA API transport not connected - cannot fetch proxied image")

        Log.d(TAG, "Fetching proxied image: $path")

        val response = transport.httpProxy(
            method = "GET",
            path = path,
            timeoutMs = 15000L
        )

        if (response.status !in 200..299) {
            throw IOException("HTTP proxy returned status ${response.status} for $path")
        }

        if (response.body.isEmpty()) {
            throw IOException("Empty response body for $path")
        }

        // Determine MIME type from response headers or default to JPEG
        val contentType = response.headers["content-type"]
            ?: response.headers["Content-Type"]
            ?: "image/jpeg"

        // Wrap the byte array in an okio Buffer -> Source
        val buffer = Buffer().write(response.body)

        return SourceResult(
            source = ImageSource(buffer, options.context),
            mimeType = contentType,
            dataSource = DataSource.NETWORK
        )
    }

    /**
     * Factory that handles `ma-proxy://` URIs.
     *
     * Coil's StringMapper converts String model data to [Uri] before fetching,
     * so this factory operates on [Uri] objects. When the URI has the "ma-proxy"
     * scheme, we extract the path + query and create a fetcher that proxies the
     * request over the DataChannel.
     */
    class Factory : Fetcher.Factory<Uri> {

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null

            // Reconstruct the server-relative path from the URI
            // e.g., ma-proxy:///imageproxy?provider=x&size=300&path=y
            //   -> /imageproxy?provider=x&size=300&path=y
            val path = data.path ?: return null
            val query = data.query
            val fullPath = if (query != null) "$path?$query" else path

            return MaProxyImageFetcher(fullPath, options)
        }
    }
}
