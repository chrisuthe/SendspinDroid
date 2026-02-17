package com.sendspindroid

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.android.material.color.DynamicColors
import com.sendspindroid.musicassistant.MaProxyImageFetcher
import com.sendspindroid.musicassistant.MaSettings

class SendSpinApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Initialize MaSettings early so it is available before any Activity or Service.
        MaSettings.initialize(this)
        // On Android 12+, applies wallpaper-derived colors to all activities.
        // On older devices, falls back to the static theme colors.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    /**
     * Provides the app-wide singleton ImageLoader with custom components.
     *
     * Includes [MaProxyImageFetcher.Factory] to handle `ma-proxy://` URIs
     * for loading images over the WebRTC DataChannel in REMOTE mode.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .components {
                add(MaProxyImageFetcher.Factory())
            }
            .build()
    }
}
