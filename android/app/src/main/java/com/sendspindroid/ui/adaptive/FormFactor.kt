package com.sendspindroid.ui.adaptive

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.compositionLocalOf

/**
 * Device form factor for adaptive UI layouts.
 *
 * Determined at the Activity level from WindowSizeClass + TV detection,
 * then provided to all composables via [LocalFormFactor].
 */
enum class FormFactor {
    /** Compact width (<600dp) - phones in portrait and landscape */
    PHONE,

    /** Medium width (600-839dp) - 7" tablets, foldables */
    TABLET_7,

    /** Expanded width (840dp+) - 10"+ tablets */
    TABLET_10,

    /** Android TV / Fire TV - 10-foot UI with D-pad navigation */
    TV
}

/**
 * CompositionLocal providing the current [FormFactor] to all composables.
 *
 * Defaults to [FormFactor.PHONE] if not explicitly provided.
 */
val LocalFormFactor = compositionLocalOf { FormFactor.PHONE }

/**
 * Determine the form factor from WindowSizeClass and TV mode.
 *
 * TV takes priority: if `isTv` is true, always returns [FormFactor.TV]
 * regardless of screen size (TVs report various window sizes).
 *
 * @param windowSizeClass The window size class from calculateWindowSizeClass()
 * @param isTv Whether the device is an Android TV (from UiModeManager)
 */
fun determineFormFactor(windowSizeClass: WindowSizeClass, isTv: Boolean): FormFactor {
    if (isTv) return FormFactor.TV

    return when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> FormFactor.PHONE
        WindowWidthSizeClass.Medium -> FormFactor.TABLET_7
        WindowWidthSizeClass.Expanded -> FormFactor.TABLET_10
        else -> FormFactor.PHONE
    }
}

/**
 * Check if the current device is an Android TV.
 *
 * Uses [UiModeManager] to detect television mode. Cache this value
 * at the Activity level since it doesn't change at runtime.
 */
fun Context.isTvDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
