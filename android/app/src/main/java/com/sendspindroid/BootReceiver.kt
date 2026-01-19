package com.sendspindroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.sendspindroid.playback.PlaybackService

/**
 * BroadcastReceiver that starts the Stay Connected mode on device boot.
 *
 * When Stay Connected is enabled in settings, this receiver will start the
 * PlaybackService in listening mode after the device finishes booting.
 * This allows SendSpin servers to discover and connect to the device
 * without requiring the user to manually open the app.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Action to start the service in listening mode.
         */
        const val ACTION_START_LISTENING = "com.sendspindroid.ACTION_START_LISTENING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.d(TAG, "Boot completed, checking Stay Connected setting")

        // Initialize UserSettings
        UserSettings.initialize(context)

        // Check if Stay Connected is enabled
        if (!UserSettings.stayConnected) {
            Log.d(TAG, "Stay Connected is disabled, not starting service")
            return
        }

        Log.i(TAG, "Stay Connected is enabled, starting PlaybackService in listening mode")

        // Start the PlaybackService
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = ACTION_START_LISTENING
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On Android O+, we need to start as foreground service
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "PlaybackService started for Stay Connected mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PlaybackService", e)
        }
    }
}
