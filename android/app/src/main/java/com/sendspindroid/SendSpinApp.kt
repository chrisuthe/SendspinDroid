package com.sendspindroid

import android.app.Application
import com.google.android.material.color.DynamicColors

class SendSpinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // On Android 12+, applies wallpaper-derived colors to all activities.
        // On older devices, falls back to the static theme colors.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
