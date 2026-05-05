package com.sendspindroid.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationHelper {
    const val CHANNEL_ID = "playback_channel"
    const val NOTIFICATION_ID = 101

    /**
     * Channel for the post-boot "tap to resume" notification. Higher
     * importance than the playback channel so the user actually sees
     * it on their lock screen / notification shade after a reboot.
     */
    const val BOOT_RESUME_CHANNEL_ID = "boot_resume_channel"
    const val BOOT_RESUME_NOTIFICATION_ID = 102

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls for audio playback"
            setShowBadge(false)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun createBootResumeChannel(context: Context) {
        val channel = NotificationChannel(
            BOOT_RESUME_CHANNEL_ID,
            "Auto-start reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tap-to-resume after a reboot when auto-start is enabled"
            setShowBadge(true)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
