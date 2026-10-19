package com.sendspindroid.playback

import android.media.AudioDeviceInfo
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The protocol's volume (0-100) is perceived loudness: roles/player/v1.md asks
 * for `amplitude = (volume / 100)^1.5`, which is `30 * log10(volume / 100)` dB.
 *
 * The app keeps volume on the device's STREAM_MUSIC index, so the hardware
 * keys work. A device applies its own curve to that index, so the index is
 * chosen from that curve ([dbAt], from AudioManager.getStreamVolumeDb), and
 * the rest of the way down to the target is gain on the AudioTrack.
 */
internal object VolumeLaw {

    /** The gain in dB the spec asks for at [volume]; minus infinity at 0. */
    fun targetDb(volume: Int): Double =
        if (volume <= 0) Double.NEGATIVE_INFINITY else 30.0 * log10(volume / 100.0)

    /** The spec's linear amplitude for [volume]. */
    fun amplitude(volume: Int): Float = (volume.coerceIn(0, 100) / 100.0).pow(1.5).toFloat()

    /** Fall back to native volume steps if vendor does not return usable volume curve */
    fun isUsableCurve(maxIndex: Int, dbAt: (Int) -> Double): Boolean {
        if (maxIndex < 2) return false
        val first = dbAt(1)
        val last = dbAt(maxIndex)
        if (!first.isFinite() || !last.isFinite()) return false
        if (first >= -TOLERANCE_DB || last <= first + TOLERANCE_DB) return false
        // Positive gain at maximum is possible in theory, but the Android API
        // describes 0 dB as the typical maximum and these tables are unsafe for
        // mapping perceived loudness. Falling back is preferable to near-mute volumes.
        if (last > TOLERANCE_DB) return false
        var previous = first
        for (index in 2..maxIndex) {
            val current = dbAt(index)
            if (!current.isFinite() || current + TOLERANCE_DB < previous) return false
            previous = current
        }
        return true
    }

    /**
     * The index for [volume]: the lowest one whose device gain reaches the
     * target, so the gain left for the track is at most 0 dB. A volume above
     * 0 never maps to index 0, which is silent.
     */
    fun indexFor(volume: Int, maxIndex: Int, dbAt: (Int) -> Double): Int {
        if (volume <= 0) return 0
        // 100% means full hardware output, even with a misleading dB table.
        if (volume >= 100) return maxIndex
        val target = targetDb(volume)
        return (1..maxIndex).firstOrNull { dbAt(it) >= target - TOLERANCE_DB } ?: maxIndex
    }

    /** Gain for the track that brings [index]'s device gain down to [volume]'s target. */
    fun trackGainFor(volume: Int, index: Int, dbAt: (Int) -> Double): Float {
        if (volume <= 0 || index <= 0 || volume >= 100) return 1f
        val residualDb = targetDb(volume) - dbAt(index)
        return 10.0.pow(residualDb / 20.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The volume that [index] stands for on its own, with no track gain: what
     * device controls report. Rounded down, so that the server sending it
     * back lands on the same index.
     */
    fun volumeFor(index: Int, dbAt: (Int) -> Double): Int {
        if (index <= 0) return 0
        val db = dbAt(index)
        if (db == Double.NEGATIVE_INFINITY) return 0
        return floor(100 * 10.0.pow(db / 30.0) + 1e-9).toInt().coerceIn(1, 100)
    }

    /**
     * Whether the index read back as [applied] after asking for [asked] marks
     * a full-volume output (HDMI with CEC, HDMI ARC/eARC): the platform keeps
     * its index at [maxIndex] whatever is asked, and AudioManager.isVolumeFixed
     * does not report it. The safe media volume warning never reads back like
     * this: it only refuses a raise above its safe index, and holds the index
     * at or below that index while it is active.
     */
    fun isFullVolumeOutput(asked: Int, applied: Int, maxIndex: Int): Boolean =
        applied == maxIndex && asked < maxIndex

    /**
     * Whether a volume just set plays as track gain alone, on a full-volume
     * output: one this set found to be so ([isFullVolumeOutput]), or one
     * found so before ([knownFullVolume]) with the index read back at max.
     * A volume near the top asks for max on most curves, and a full-volume
     * output then reads back as asked, so only one known before tells.
     */
    fun isFullVolumeSet(knownFullVolume: Boolean, asked: Int, applied: Int, maxIndex: Int): Boolean =
        isFullVolumeOutput(asked, applied, maxIndex) || (knownFullVolume && applied == maxIndex)

    /**
     * The volume to restore when the volume control starts on an output, or
     * media moves to it. On a fixed-volume output, or a full-volume one
     * ([fullVolume], as for [outputChange]), the whole volume is track gain
     * and Android keeps none of it the way it keeps an index, so it is the
     * one last set on that output ([stored]). Null where none was set, and
     * on any other output, whose index stands for its volume.
     * roles/player/v1.md, "Volume and mute": "Persisting `volume` and
     * `muted` across reboots is RECOMMENDED for players."
     */
    fun restoredVolume(volumeFixed: Boolean, fullVolume: Boolean, stored: Int?): Int? =
        if (volumeFixed || fullVolume) stored else null

    /** What a change in the outputs connected means for the volume ([outputChange]). */
    enum class OutputChange {
        /** A fixed-volume or full-volume output: no index to set, the track gain is the whole volume. */
        KEEP_VOLUME,

        /** Media plays on the same output at the same index: nothing that plays changed. */
        NONE,

        /** Media moved to an output with its own stored index and curve. */
        DEVICE_VOLUME,
    }

    /**
     * What to do once outputs were connected or removed, from the STREAM_MUSIC
     * [index] and the [output] media plays on now, against the ones the volume
     * was last set or synced for. [fullVolume]: media plays on an output found
     * to be full-volume before ([isFullVolumeOutput]), with the index at max,
     * which AudioManager.isVolumeFixed does not report.
     */
    fun outputChange(
        volumeFixed: Boolean,
        fullVolume: Boolean,
        index: Int,
        lastIndex: Int,
        output: Int?,
        lastOutput: Int?,
    ): OutputChange =
        when {
            volumeFixed || fullVolume -> OutputChange.KEEP_VOLUME
            index == lastIndex && output == lastOutput -> OutputChange.NONE
            else -> OutputChange.DEVICE_VOLUME
        }

    /**
     * The output media plays on, of the [outputs] connected (AudioDeviceInfo
     * types), where the platform cannot be asked (below API 33): AudioPolicy's
     * order for media on Android 9 and 10 (enginedefault Engine.cpp,
     * STRATEGY_MEDIA). Android 11 and 12 route to whichever removable output
     * was connected last instead, so with two connected this can pick the
     * other one; the volume curve only differs by category (headset, speaker,
     * external media), which most of them share.
     */
    fun mediaOutputType(outputs: Collection<Int>): Int =
        MEDIA_OUTPUT_ORDER.firstOrNull { it in outputs } ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    // Media falls back to the speaker after these
    private val MEDIA_OUTPUT_ORDER = listOf(
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_HDMI,
    )

    // Rounding in a device's dB table must not push a volume to the next index
    private const val TOLERANCE_DB = 0.01
}
