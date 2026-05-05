package com.sendspindroid

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sendspindroid.playback.NotificationHelper
import com.sendspindroid.playback.PlaybackService

/**
 * Receives BOOT_COMPLETED broadcast and resumes auto-connect if the user
 * opted in.
 *
 * On Android 14 and below, the service is started directly via
 * `startForegroundService`. On Android 15+ that path is disallowed for
 * mediaPlayback foreground services (`ForegroundServiceStartNotAllowed
 * Exception`); instead a notification is posted that, when tapped,
 * launches the same auto-connect intent. The user's tap is treated as
 * user-initiated and bypasses the boot-time restriction.
 *
 * Opt-in only: requires both the "Auto-start on boot" setting and a
 * default server. Does not launch any UI.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

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

        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_AUTO_CONNECT
            putExtra(PlaybackService.EXTRA_SERVER_ID, defaultServer.id)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.i(TAG, "Auto-start on boot: posting tap-to-resume notification for ${defaultServer.name}")
            postResumeNotification(context, serviceIntent, defaultServer.name)
        } else {
            Log.i(TAG, "Auto-start on boot: connecting to ${defaultServer.name}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    /**
     * Show a notification with a tap action that starts the foreground
     * service. The tap fires `PendingIntent.getForegroundService`, which
     * is treated by the system as user-initiated and is not subject to
     * the BOOT_COMPLETED foreground-service-type restriction.
     *
     * Silently no-ops if the user has revoked POST_NOTIFICATIONS on
     * Android 13+; the user will need to manually open the app.
     */
    private fun postResumeNotification(
        context: Context,
        serviceIntent: Intent,
        serverName: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; cannot post resume notification")
            return
        }

        NotificationHelper.createBootResumeChannel(context)

        val tapIntent = PendingIntent.getForegroundService(
            context,
            0,
            serviceIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.BOOT_RESUME_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Tap to connect to $serverName")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NotificationHelper.BOOT_RESUME_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post resume notification", e)
        }
    }
}
