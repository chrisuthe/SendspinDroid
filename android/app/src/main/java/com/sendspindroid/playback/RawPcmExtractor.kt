package com.sendspindroid.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Custom extractor for raw PCM audio data from the Go player.
 *
 * ExoPlayer's architecture expects audio in container formats (WAV, MP3, FLAC, etc.)
 * that have headers describing the audio format. Our Go player provides raw PCM bytes
 * without any container. This extractor bridges the gap by:
 *
 * 1. Telling ExoPlayer "yes, I can handle this stream" (sniff returns true)
 * 2. Defining the exact PCM format upfront (48kHz, stereo, 16-bit)
 * 3. Passing raw bytes directly to the audio renderer as samples
 *
 * ## Why This Is Needed
 * ProgressiveMediaSource tries to auto-detect format using extractors.
 * Without a custom extractor, ExoPlayer throws:
 * ```
 * UnrecognizedInputFormatException: None of the available extractors
 * (FlvExtractor, FlacExtractor, WavExtractor, ...) could read the stream
 * ```
 *
 * ## Audio Format Specification
 * Must match what the Go player provides:
 * - Sample rate: 48,000 Hz
 * - Channels: 2 (stereo)
 * - Encoding: PCM 16-bit signed little-endian
 * - Bytes per sample frame: 4 (2 bytes × 2 channels)
 *
 * @see GoPlayerMediaSourceFactory
 */
@UnstableApi
class RawPcmExtractor : Extractor {

    companion object {
        private const val TAG = "RawPcmExtractor"

        // Audio format constants - MUST match Go player output
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
        private const val PCM_ENCODING = C.ENCODING_PCM_16BIT

        // Bytes per sample frame: 2 bytes per sample × 2 channels
        private const val BYTES_PER_FRAME = 4

        // Read buffer size - process 4096 bytes at a time (~21ms of audio)
        // This balances latency vs overhead (smaller = more method calls)
        private const val READ_BUFFER_SIZE = 4096

        // Track ID for the audio track (we only have one track)
        private const val TRACK_ID = 0
    }

    /** Output for audio samples - set during init() */
    private var trackOutput: TrackOutput? = null

    /** Scratch buffer for reading and processing audio data */
    private val readBuffer = ParsableByteArray(READ_BUFFER_SIZE)

    /** Running timestamp for samples (microseconds) */
    private var sampleTimeUs: Long = 0

    /** Whether we've completed initialization */
    private var outputInitialized = false

    /**
     * Attempts to detect if this extractor can handle the input.
     *
     * For our custom data source, we always return true because:
     * 1. GoPlayerDataSource only provides raw PCM
     * 2. We're the only extractor registered for this stream
     * 3. There's no header to detect - it's raw bytes
     *
     * @param input The input to examine
     * @return Always true - we know our data source provides raw PCM
     */
    override fun sniff(input: ExtractorInput): Boolean {
        Log.d(TAG, "sniff() called - returning true for raw PCM")
        // We don't need to actually read/examine the input
        // Our custom data source guarantees raw PCM format
        return true
    }

    /**
     * Initializes the extractor with the output where samples should be written.
     *
     * This is where we tell ExoPlayer:
     * 1. We have one audio track
     * 2. The exact audio format (48kHz stereo 16-bit PCM)
     * 3. This is a live/unseekable stream
     *
     * @param output The output to write extracted data to
     */
    override fun init(output: ExtractorOutput) {
        Log.d(TAG, "init() - configuring audio track")

        // Request a track output for our audio track
        trackOutput = output.track(TRACK_ID, C.TRACK_TYPE_AUDIO)

        // Define the exact PCM format
        // This tells ExoPlayer's audio renderer exactly what to expect
        val format = Format.Builder()
            .setId("raw_pcm_audio")
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(CHANNEL_COUNT)
            .setSampleRate(SAMPLE_RATE)
            .setPcmEncoding(PCM_ENCODING)
            .build()

        // Set the format on the track output
        trackOutput?.format(format)

        // Define seek behavior - live stream, no seeking
        // C.TIME_UNSET means duration is unknown (infinite live stream)
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))

        // Signal that we're done defining tracks
        output.endTracks()

        outputInitialized = true
        Log.d(TAG, "Extractor initialized: ${SAMPLE_RATE}Hz, ${CHANNEL_COUNT}ch, PCM16")
    }

    /**
     * Reads from the input and outputs audio samples.
     *
     * For raw PCM, we simply:
     * 1. Read bytes from input
     * 2. Calculate the timestamp based on sample count
     * 3. Output as a sample with KEY_FRAME flag (all PCM frames are key frames)
     *
     * @param input The input to read from (wraps our GoPlayerDataSource)
     * @param seekPosition Used for seeking (ignored for live streams)
     * @return RESULT_CONTINUE to keep reading, RESULT_END_OF_INPUT when stream ends
     */
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = trackOutput ?: run {
            Log.e(TAG, "TrackOutput is null - init() not called?")
            return Extractor.RESULT_END_OF_INPUT
        }

        // Read up to READ_BUFFER_SIZE bytes from input
        val bytesRead = input.read(readBuffer.data, 0, READ_BUFFER_SIZE)

        when {
            bytesRead == C.RESULT_END_OF_INPUT -> {
                Log.d(TAG, "End of input stream")
                return Extractor.RESULT_END_OF_INPUT
            }
            bytesRead == 0 -> {
                // No data available but stream not ended
                // This shouldn't happen with our DataSource, but handle it
                return Extractor.RESULT_CONTINUE
            }
            bytesRead > 0 -> {
                // Calculate duration of this chunk in microseconds
                // duration = (bytes / bytesPerFrame) * (1_000_000 / sampleRate)
                val frameCount = bytesRead / BYTES_PER_FRAME
                val durationUs = (frameCount * 1_000_000L) / SAMPLE_RATE

                // Write sample data to the track output
                readBuffer.setPosition(0)
                readBuffer.setLimit(bytesRead)
                output.sampleData(readBuffer, bytesRead)

                // Write sample metadata
                // For live streams, we track time ourselves starting from 0
                output.sampleMetadata(
                    /* timeUs = */ sampleTimeUs,
                    /* flags = */ C.BUFFER_FLAG_KEY_FRAME,  // All PCM samples are "key frames"
                    /* size = */ bytesRead,
                    /* offset = */ 0,  // No trailing bytes
                    /* cryptoData = */ null
                )

                // Advance timestamp for next sample
                sampleTimeUs += durationUs

                return Extractor.RESULT_CONTINUE
            }
            else -> {
                // Negative value other than END_OF_INPUT is an error
                Log.e(TAG, "Unexpected read result: $bytesRead")
                return Extractor.RESULT_END_OF_INPUT
            }
        }
    }

    /**
     * Handles seek requests.
     *
     * For live streams, seeking is not supported. We reset our timestamp
     * tracking in case ExoPlayer tries to restart the stream.
     *
     * @param position The byte position to seek to (ignored)
     * @param timeUs The time to seek to (ignored)
     */
    override fun seek(position: Long, timeUs: Long) {
        Log.d(TAG, "seek() called - resetting timestamp (live stream, no real seeking)")
        // Reset timestamp tracking for stream restart
        sampleTimeUs = 0
    }

    /**
     * Releases resources used by the extractor.
     *
     * Called when ExoPlayer is done with this extractor instance.
     */
    override fun release() {
        Log.d(TAG, "release() called")
        trackOutput = null
        outputInitialized = false
        sampleTimeUs = 0
    }
}
