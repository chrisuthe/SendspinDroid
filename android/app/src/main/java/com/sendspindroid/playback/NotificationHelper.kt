package com.sendspindroid.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationHelper {
    const val CHANNEL_ID = "playback_channel"
    const val NOTIFICATION_ID = 101

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
}
