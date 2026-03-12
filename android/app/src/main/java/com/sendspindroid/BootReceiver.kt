package com.sendspindroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.sendspindroid.playback.PlaybackService

/**
 * Receives BOOT_COMPLETED broadcast and starts PlaybackService if auto-start is enabled.
 *
 * Opt-in only: requires both the "Auto-start on boot" setting to be enabled
 * and a default server to be configured. Does not launch any UI.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Initialize settings so we can read preferences
        UserSettings.initialize(context)
        UnifiedServerRepository.initialize(context)

        if (!UserSettings.autoStartOnBoot) {
            Log.d(TAG, "Auto-start on boot is disabled, skipping")
            return
        }

        val defaultServer = UnifiedServerRepository.getDefaultServer()
        if (defaultServer == null) {
            Log.w(TAG, "Auto-start enabled but no default server configured, skipping")
            return
        }

        Log.i(TAG, "Auto-start on boot: connecting to ${defaultServer.name}")

        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_AUTO_CONNECT
            putExtra(PlaybackService.EXTRA_SERVER_ID, defaultServer.id)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
