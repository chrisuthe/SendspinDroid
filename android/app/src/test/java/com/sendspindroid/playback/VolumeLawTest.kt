package com.sendspindroid.playback

import android.media.AudioDeviceInfo
import com.sendspindroid.playback.VolumeLaw.OutputChange
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLawTest {

    // AOSP's default media curve (default_volume_tables.xml): -58 dB at 1%,
    // -40 dB at 20%, -17 dB at 60%, 0 dB at 100%, interpolated over a 15-step
    // index the way AudioPolicy does it.
    private val maxIndex = 15
    private val points = listOf(1 to -58.0, 20 to -40.0, 60 to -17.0, 100 to 0.0)
    private val dbAt: (Int) -> Double = { index ->
        if (index == 0) Double.NEGATIVE_INFINITY else {
            val percent = (index * 100.0) / maxIndex
            val (lo, hi) = points.zipWithNext().first { (_, b) -> percent <= b.first }
            lo.second + (hi.second - lo.second) * (percent - lo.first) / (hi.first - lo.first)
        }
    }

    /** What the listener gets: the device gain at the index plus the track gain. */
    private fun outputDb(volume: Int): Double {
        val index = VolumeLaw.indexFor(volume, maxIndex, dbAt)
        return dbAt(index) + 20 * log10(VolumeLaw.trackGainFor(volume, index, dbAt).toDouble())
    }

    @Test
    fun `the output follows the spec curve at every volume`() {
        for (volume in 1..100) {
            val errorDb = outputDb(volume) - 30 * log10(volume / 100.0)
            assertTrue("volume $volume off by $errorDb dB", abs(errorDb) < 0.01)
        }
    }

    @Test
    fun `the spec's own examples`() {
        assertEquals(0.0, VolumeLaw.targetDb(100), 1e-9)
        assertEquals(-9.03, VolumeLaw.targetDb(50), 0.01)
        assertEquals(0.3536f, VolumeLaw.amplitude(50), 0.0001f)
        assertEquals(0f, VolumeLaw.amplitude(0), 0f)
    }

    @Test
    fun `track gain only ever attenuates`() {
        for (volume in 0..100) {
            val index = VolumeLaw.indexFor(volume, maxIndex, dbAt)
            val gain = VolumeLaw.trackGainFor(volume, index, dbAt)
            assertTrue("volume $volume gain $gain", gain in 0f..1f)
        }
    }

    @Test
    fun `valid Android dB curve is retained`() {
        assertTrue(VolumeLaw.isUsableCurve(maxIndex, dbAt))
    }

    @Test
    fun `zero to one dB table is invalid on every device`() {
        val broken: (Int) -> Double = { it.toDouble() / maxIndex }
        assertFalse(VolumeLaw.isUsableCurve(maxIndex, broken))
        assertEquals(maxIndex, VolumeLaw.indexFor(100, maxIndex, broken))
        assertEquals(1f, VolumeLaw.trackGainFor(100, maxIndex, broken), 0f)
    }

    @Test
    fun `flat reversed nonfinite and positive dB tables are invalid`() {
        val flat: (Int) -> Double = { if (it == 0) Double.NEGATIVE_INFINITY else 0.0 }
        val reversed: (Int) -> Double = { -it.toDouble() }
        val nan: (Int) -> Double = { if (it == 8) Double.NaN else dbAt(it) }
        val positive: (Int) -> Double = { dbAt(it) + 3.0 }
        assertFalse(VolumeLaw.isUsableCurve(maxIndex, flat))
        assertFalse(VolumeLaw.isUsableCurve(maxIndex, reversed))
        assertFalse(VolumeLaw.isUsableCurve(maxIndex, nan))
        assertFalse(VolumeLaw.isUsableCurve(maxIndex, positive))
    }

    @Test
    fun `100 percent uses maximum step even on malformed curve`() {
        val misleading: (Int) -> Double = { 0.0 }
        assertEquals(maxIndex, VolumeLaw.indexFor(100, maxIndex, misleading))
        assertEquals(1f, VolumeLaw.trackGainFor(100, maxIndex, misleading), 0f)
        assertEquals(0, VolumeLaw.indexFor(0, maxIndex, misleading))
    }

    @Test
    fun `a volume above 0 is never the silent index`() {
        assertEquals(0, VolumeLaw.indexFor(0, maxIndex, dbAt))
        for (volume in 1..100) assertTrue(VolumeLaw.indexFor(volume, maxIndex, dbAt) >= 1)
    }

    @Test
    fun `device steps report the volume their gain stands for`() {
        assertEquals(0, VolumeLaw.volumeFor(0, dbAt))
        assertEquals(100, VolumeLaw.volumeFor(maxIndex, dbAt))
        // Index 8 of 15 sits at -21 dB on the default curve: 20% louder than
        // half volume would be on a linear index, much quieter than 53.
        val v = VolumeLaw.volumeFor(8, dbAt)
        assertEquals(-21.0, 30 * log10(v / 100.0), 0.3)
        // Rising with the index
        val reported = (1..maxIndex).map { VolumeLaw.volumeFor(it, dbAt) }
        assertEquals(reported.sorted(), reported)
    }

    @Test
    fun `a device step maps back to itself`() {
        for (index in 1..maxIndex) {
            val volume = VolumeLaw.volumeFor(index, dbAt)
            assertEquals("index $index -> volume $volume", index, VolumeLaw.indexFor(volume, maxIndex, dbAt))
        }
    }

    @Test
    fun `an index held at max when less was asked is a full-volume output`() {
        assertTrue(VolumeLaw.isFullVolumeOutput(asked = 8, applied = maxIndex, maxIndex = maxIndex))
        // Max asked for and set
        assertFalse(VolumeLaw.isFullVolumeOutput(asked = maxIndex, applied = maxIndex, maxIndex = maxIndex))
        // The safe media volume warning holds the index below what was asked
        assertFalse(VolumeLaw.isFullVolumeOutput(asked = 13, applied = 10, maxIndex = maxIndex))
        // Volume 0 mutes, which reads back as index 0
        assertFalse(VolumeLaw.isFullVolumeOutput(asked = 0, applied = 0, maxIndex = maxIndex))
    }

    @Test
    fun `a volume set on a full-volume output is track gain alone`() {
        // Found so by this set
        assertTrue(VolumeLaw.isFullVolumeSet(knownFullVolume = false, asked = 8, applied = maxIndex, maxIndex = maxIndex))
        // Found before: a volume near the top asks for max, which reads back as asked
        val asked = VolumeLaw.indexFor(90, maxIndex, dbAt)
        assertEquals(maxIndex, asked)
        assertTrue(VolumeLaw.isFullVolumeSet(knownFullVolume = true, asked = asked, applied = maxIndex, maxIndex = maxIndex))
        // Not found before, the same set tells nothing
        assertFalse(VolumeLaw.isFullVolumeSet(knownFullVolume = false, asked = asked, applied = maxIndex, maxIndex = maxIndex))
        // A variable sink of a type found full-volume before holds the index it is given
        assertFalse(VolumeLaw.isFullVolumeSet(knownFullVolume = true, asked = 8, applied = 8, maxIndex = maxIndex))
    }

    @Test
    fun `a fixed-volume or full-volume output starts at the volume last set there`() {
        assertEquals(30, VolumeLaw.restoredVolume(volumeFixed = true, fullVolume = false, stored = 30))
        assertEquals(30, VolumeLaw.restoredVolume(volumeFixed = false, fullVolume = true, stored = 30))
        // None set there yet
        assertNull(VolumeLaw.restoredVolume(volumeFixed = true, fullVolume = false, stored = null))
        assertNull(VolumeLaw.restoredVolume(volumeFixed = false, fullVolume = true, stored = null))
    }

    @Test
    fun `a variable output starts from its own index`() {
        // Its index stands for the volume, and Android keeps it, even where a
        // volume was kept for a full-volume output of the same type
        assertNull(VolumeLaw.restoredVolume(volumeFixed = false, fullVolume = false, stored = 30))
    }

    @Test
    fun `a fixed-volume output keeps its volume whatever outputs come or go`() {
        assertEquals(OutputChange.KEEP_VOLUME, VolumeLaw.outputChange(true, false, maxIndex, maxIndex, HDMI, HDMI))
        // Media moved there from a variable output
        assertEquals(OutputChange.KEEP_VOLUME, VolumeLaw.outputChange(true, false, maxIndex, 8, HDMI, A2DP))
    }

    @Test
    fun `a full-volume output keeps its volume when media comes back to it`() {
        // Bluetooth headphones gone, media back on HDMI with CEC: the index is held at max
        assertEquals(OutputChange.KEEP_VOLUME, VolumeLaw.outputChange(false, true, maxIndex, 8, HDMI, A2DP))
        assertEquals(OutputChange.KEEP_VOLUME, VolumeLaw.outputChange(false, true, maxIndex, maxIndex, HDMI, HDMI))
    }

    @Test
    fun `a sink that leaves media where it was changes nothing`() {
        assertEquals(OutputChange.NONE, VolumeLaw.outputChange(false, false, 8, 8, SPEAKER, SPEAKER))
        // HDMI at max, not found to be full-volume yet
        assertEquals(OutputChange.NONE, VolumeLaw.outputChange(false, false, maxIndex, maxIndex, HDMI, HDMI))
    }

    @Test
    fun `media on another output or index takes that device volume`() {
        assertEquals(OutputChange.DEVICE_VOLUME, VolumeLaw.outputChange(false, false, 5, 8, SPEAKER, SPEAKER))
        // Same index, other curve
        assertEquals(OutputChange.DEVICE_VOLUME, VolumeLaw.outputChange(false, false, 8, 8, A2DP, SPEAKER))
    }

    @Test
    fun `below API 33 media goes to the output AudioPolicy routes it to`() {
        val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        assertEquals(SPEAKER, VolumeLaw.mediaOutputType(listOf(earpiece, SPEAKER)))
        assertEquals(SPEAKER, VolumeLaw.mediaOutputType(emptyList()))
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        assertEquals(wired, VolumeLaw.mediaOutputType(listOf(earpiece, SPEAKER, wired)))
        // A hands-free car kit (SCO only) carries no media
        assertEquals(SPEAKER, VolumeLaw.mediaOutputType(listOf(SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        // Android 9 and 10 put A2DP ahead of wired outputs, and both ahead of HDMI
        assertEquals(A2DP, VolumeLaw.mediaOutputType(listOf(SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, A2DP)))
        // A hearing aid ahead of A2DP, and line out between wired headphones and a wired headset
        val hearingAid = AudioDeviceInfo.TYPE_HEARING_AID
        assertEquals(hearingAid, VolumeLaw.mediaOutputType(listOf(SPEAKER, A2DP, hearingAid)))
        val line = AudioDeviceInfo.TYPE_LINE_ANALOG
        assertEquals(line, VolumeLaw.mediaOutputType(listOf(SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, line)))
        assertEquals(wired, VolumeLaw.mediaOutputType(listOf(SPEAKER, line, wired)))
        val usb = AudioDeviceInfo.TYPE_USB_HEADSET
        assertEquals(usb, VolumeLaw.mediaOutputType(listOf(SPEAKER, HDMI, usb)))
        assertEquals(HDMI, VolumeLaw.mediaOutputType(listOf(SPEAKER, HDMI)))
    }

    private companion object {
        const val SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        const val A2DP = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        const val HDMI = AudioDeviceInfo.TYPE_HDMI
    }
}
