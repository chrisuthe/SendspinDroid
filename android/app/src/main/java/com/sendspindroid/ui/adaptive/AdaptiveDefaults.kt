package com.sendspindroid.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Adaptive dimension and spacing constants per form factor.
 *
 * These provide sensible defaults for each device type. Individual screens
 * can override these when their layout needs differ from the defaults.
 *
 * Usage:
 * ```
 * val formFactor = LocalFormFactor.current
 * val columns = AdaptiveDefaults.gridColumns(formFactor)
 * val padding = AdaptiveDefaults.screenPadding(formFactor)
 * ```
 */
object AdaptiveDefaults {

    // -- Grid Layouts --

    /** Number of columns for grid displays (albums, artists, etc.) */
    fun gridColumns(formFactor: FormFactor): Int = when (formFactor) {
        FormFactor.PHONE -> 2
        FormFactor.TABLET_7 -> 3
        FormFactor.TABLET_10 -> 4
        FormFactor.TV -> 5
    }

    // -- Card Sizes --

    /** Width for media cards in horizontal carousels */
    fun cardWidth(formFactor: FormFactor): Dp = when (formFactor) {
        FormFactor.PHONE -> 160.dp
        FormFactor.TABLET_7 -> 180.dp
        FormFactor.TABLET_10 -> 200.dp
        FormFactor.TV -> 220.dp
    }

    // -- Screen Padding --

    /** Horizontal screen padding / content margins */
    fun screenPadding(formFactor: FormFactor): Dp = when (formFactor) {
        FormFactor.PHONE -> 16.dp
        FormFactor.TABLET_7 -> 24.dp
        FormFactor.TABLET_10 -> 32.dp
        FormFactor.TV -> 48.dp  // Overscan-safe margin
    }

    // -- Text Sizes --

    /** Title text size (e.g., Now Playing track title, section headers) */
    fun titleTextSize(formFactor: FormFactor): TextUnit = when (formFactor) {
        FormFactor.PHONE -> 24.sp
        FormFactor.TABLET_7 -> 28.sp
        FormFactor.TABLET_10 -> 32.sp
        FormFactor.TV -> 36.sp
    }

    /** Body text size (e.g., artist name, metadata) */
    fun bodyTextSize(formFactor: FormFactor): TextUnit = when (formFactor) {
        FormFactor.PHONE -> 14.sp
        FormFactor.TABLET_7 -> 14.sp
        FormFactor.TABLET_10 -> 16.sp
        FormFactor.TV -> 20.sp  // 10-foot UI minimum
    }

    /** Caption/label text size */
    fun captionTextSize(formFactor: FormFactor): TextUnit = when (formFactor) {
        FormFactor.PHONE -> 12.sp
        FormFactor.TABLET_7 -> 12.sp
        FormFactor.TABLET_10 -> 14.sp
        FormFactor.TV -> 16.sp
    }

    // -- Playback Controls --

    /** Large playback button size (play/pause) */
    fun playButtonSize(formFactor: FormFactor): Dp = when (formFactor) {
        FormFactor.PHONE -> 72.dp
        FormFactor.TABLET_7 -> 80.dp
        FormFactor.TABLET_10 -> 88.dp
        FormFactor.TV -> 96.dp
    }

    /** Small playback button size (prev/next/group) */
    fun controlButtonSize(formFactor: FormFactor): Dp = when (formFactor) {
        FormFactor.PHONE -> 56.dp
        FormFactor.TABLET_7 -> 64.dp
        FormFactor.TABLET_10 -> 72.dp
        FormFactor.TV -> 80.dp
    }

    // -- Album Art --

    /** Maximum album art size for Now Playing */
    fun albumArtMaxSize(formFactor: FormFactor): Dp = when (formFactor) {
        FormFactor.PHONE -> 280.dp
        FormFactor.TABLET_7 -> 320.dp
        FormFactor.TABLET_10 -> 400.dp
        FormFactor.TV -> 500.dp
    }

    // -- Layout Decisions --

    /** Whether Now Playing should use side-by-side layout (art left, controls right) */
    fun useSideBySideNowPlaying(formFactor: FormFactor, isLandscape: Boolean): Boolean =
        when (formFactor) {
            FormFactor.PHONE -> isLandscape
            FormFactor.TABLET_7 -> true  // Always side-by-side on 7" tablets
            FormFactor.TABLET_10 -> true
            FormFactor.TV -> true
        }

    /** Art width fraction when using side-by-side layout */
    fun sideBySideArtFraction(formFactor: FormFactor): Float = when (formFactor) {
        FormFactor.PHONE -> 0.45f
        FormFactor.TABLET_7 -> 0.40f
        FormFactor.TABLET_10 -> 0.45f
        FormFactor.TV -> 0.55f
    }

    /** Whether to show the mini player (TV doesn't need it) */
    fun showMiniPlayer(formFactor: FormFactor): Boolean =
        formFactor != FormFactor.TV

    // -- Inline Queue Panel (Tablet) --

    /** Whether Now Playing should show an inline queue panel alongside controls */
    fun showInlineQueuePanel(formFactor: FormFactor): Boolean =
        formFactor == FormFactor.TABLET_7 || formFactor == FormFactor.TABLET_10

    /** Weight of the Now Playing controls column when inline queue is shown */
    fun nowPlayingControlsWeight(formFactor: FormFactor): Float = when (formFactor) {
        FormFactor.TABLET_7 -> 0.55f
        FormFactor.TABLET_10 -> 0.50f
        else -> 1.0f
    }

    /** Weight of the inline queue panel column */
    fun inlineQueueWeight(formFactor: FormFactor): Float = when (formFactor) {
        FormFactor.TABLET_7 -> 0.45f
        FormFactor.TABLET_10 -> 0.50f
        else -> 0.0f
    }
}
