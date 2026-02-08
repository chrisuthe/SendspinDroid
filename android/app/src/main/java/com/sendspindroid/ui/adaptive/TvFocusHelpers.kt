package com.sendspindroid.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TV focus modifier that adds:
 * - Focusable behavior for D-pad navigation
 * - Visible focus ring (3dp border in primary color) when focused
 * - Subtle scale animation (1.05x) on focus for visual feedback
 *
 * Use this on all interactive elements (buttons, cards, list items) on TV.
 * On non-TV devices, this just adds focusable() without visual effects.
 *
 * @param borderWidth Width of the focus ring border
 * @param cornerRadius Corner radius of the focus ring
 * @param focusScale Scale factor when focused (1.0 = no scale)
 */
fun Modifier.tvFocusable(
    borderWidth: Dp = 3.dp,
    cornerRadius: Dp = 12.dp,
    focusScale: Float = 1.05f
): Modifier = composed {
    val formFactor = LocalFormFactor.current

    if (formFactor != FormFactor.TV) {
        return@composed this.focusable()
    }

    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        label = "tv_focus_scale"
    )
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    this
        .scale(scale)
        .border(
            width = borderWidth,
            color = borderColor,
            shape = RoundedCornerShape(cornerRadius)
        )
        .onFocusChanged { isFocused = it.isFocused }
        .focusable()
}

/**
 * Applies overscan-safe padding on TV devices (48dp all sides).
 * On non-TV devices, applies no padding.
 *
 * Use this on the outermost container of each screen to ensure
 * content is visible within the TV's safe display area.
 */
fun Modifier.overscanSafe(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(48.dp)
    } else {
        this
    }
}

/**
 * Applies overscan-safe horizontal padding only on TV devices (48dp left/right).
 * Useful when vertical overscan is handled by scroll containers.
 */
fun Modifier.overscanSafeHorizontal(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(horizontal = 48.dp)
    } else {
        this
    }
}
