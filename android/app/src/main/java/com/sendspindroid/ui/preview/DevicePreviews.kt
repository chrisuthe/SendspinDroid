package com.sendspindroid.ui.preview

import androidx.compose.ui.tooling.preview.Preview

/**
 * Multi-device preview annotations for wireframing layouts across form factors.
 *
 * Use @PhonePreviews for phone portrait + landscape.
 * Use @TabletPreviews for 7" and 10" tablet layouts.
 * Use @AllDevicePreviews for the complete matrix (phone, tablet, TV).
 *
 * Usage:
 * ```
 * @AllDevicePreviews
 * @Composable
 * private fun MyScreenPreview() { ... }
 * ```
 */

// -- Phone --

@Preview(
    name = "Phone Portrait",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    group = "Phone"
)
@Preview(
    name = "Phone Landscape",
    showBackground = true,
    widthDp = 640,
    heightDp = 360,
    group = "Phone"
)
annotation class PhonePreviews

// -- Tablet --

@Preview(
    name = "Tablet 7\" Portrait",
    showBackground = true,
    widthDp = 600,
    heightDp = 1024,
    group = "Tablet"
)
@Preview(
    name = "Tablet 7\" Landscape",
    showBackground = true,
    widthDp = 1024,
    heightDp = 600,
    group = "Tablet"
)
@Preview(
    name = "Tablet 10\" Portrait",
    showBackground = true,
    widthDp = 800,
    heightDp = 1280,
    group = "Tablet"
)
@Preview(
    name = "Tablet 10\" Landscape",
    showBackground = true,
    widthDp = 1280,
    heightDp = 800,
    group = "Tablet"
)
annotation class TabletPreviews

// -- All Devices --

@PhonePreviews
@TabletPreviews
annotation class AllDevicePreviews
