package com.sendspindroid.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Custom DataSource that bridges Go player's audio output to ExoPlayer.
 *
 * This class implements ExoPlayer's DataSource interface to read PCM audio data
 * from the Go player's readAudioData() method and provide it to ExoPlayer's
 * playback pipeline.
 *
 * ## Threading Model
 * - All methods are called on ExoPlayer's dedicated playback thread (not UI thread)
 * - read() is designed to block until data is available (this is expected behavior)
 * - No synchronization needed for internal state (single-threaded access)
 *
 * ## Buffer Management
 * - Uses an internal 8KB buffer to match Go player's chunk size
 * - Data is copied from internal buffer to ExoPlayer's target buffer
 * - System.arraycopy() is used for efficient buffer copying
 *
 * ## Audio Format
 * - PCM 16-bit signed, little-endian
 * - Stereo (2 channels)
 * - 48kHz sample rate
 *
 * @param goPlayer The Go player instance that provides audio data via readAudioData()
 */
class GoPlayerDataSource(
    private val goPlayer: player.Player_
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "GoPlayerDataSource"

        /** Buffer size matching Go player's audio chunk size */
        private const val BUFFER_SIZE = 8192

        /** Custom URI scheme for SendSpin streams */
        private const val URI_SCHEME = "sendspin"
    }

    /** Internal buffer for reading audio data from Go player */
    private val buffer = ByteArray(BUFFER_SIZE)

    /**
     * Ring buffer to hold excess data when ExoPlayer requests less than we read.
     * This prevents audio data loss and maintains synchronization.
     *
     * Size is 4x the read buffer to handle timing variations:
     * - At 48kHz stereo 16-bit = 192KB/sec
     * - 32KB buffer = ~166ms of audio headroom
     */
    private val ringBuffer = ByteArray(BUFFER_SIZE * 4)

    /** Current position in ring buffer where valid data starts */
    private var ringBufferReadPos = 0

    /** Number of valid bytes currently in ring buffer */
    private var ringBufferAvailable = 0

    /** Tracks remaining bytes for the stream (unknown for live streams) */
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** Whether the data source has been opened */
    private var opened = false

    /** The DataSpec provided during open(), stored for getUri() */
    private var dataSpec: DataSpec? = null

    /**
     * Opens the data source for reading.
     *
     * Called by ExoPlayer when it's ready to start reading audio data.
     * For live streams, we return C.LENGTH_UNSET since the total length is unknown.
     *
     * @param dataSpec Contains the URI and other parameters for the stream
     * @return The total content length, or C.LENGTH_UNSET for live/unknown length
     * @throws IOException if already opened or connection fails
     */
    override fun open(dataSpec: DataSpec): Long {
        if (opened) {
            throw IOException("DataSource already opened")
        }

        Log.d(TAG, "Opening GoPlayerDataSource for URI: ${dataSpec.uri}")

        // Store the dataSpec for getUri() calls
        this.dataSpec = dataSpec

        // Notify ExoPlayer's transfer listeners that data transfer is starting
        // This enables bandwidth tracking and buffering statistics
        transferStarted(dataSpec)

        opened = true
        bytesRemaining = C.LENGTH_UNSET.toLong()

        // Return unknown length - this is a live audio stream
        // ExoPlayer will read continuously until we return RESULT_END_OF_INPUT
        return C.LENGTH_UNSET.toLong()
    }

    /**
     * Reads audio data from the Go player into the target buffer.
     *
     * This method uses a ring buffer to preserve audio data when ExoPlayer
     * requests less than we read from Go player. This prevents data loss
     * and maintains audio synchronization.
     *
     * This method blocks until data is available from the Go player.
     * ExoPlayer expects this behavior - it runs on a dedicated playback thread.
     *
     * @param target The buffer to fill with audio data
     * @param offset The starting position in the target buffer
     * @param length The maximum number of bytes to read
     * @return Number of bytes read, or C.RESULT_END_OF_INPUT if stream ended
     * @throws IOException if not opened or read fails
     */
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        // Special case: zero-length read request
        if (length == 0) {
            return 0
        }

        // Validate that we're in an opened state
        if (!opened) {
            throw IOException("DataSource not opened - call open() first")
        }

        try {
            // If ring buffer doesn't have enough data, read more from Go player
            if (ringBufferAvailable < length) {
                fillRingBuffer()
            }

            // If still no data available after trying to fill, signal end of input
            if (ringBufferAvailable == 0) {
                Log.d(TAG, "No data available after fill attempt - signaling end of input")
                return C.RESULT_END_OF_INPUT
            }

            // Copy requested amount (or what's available) from ring buffer
            val bytesToCopy = minOf(ringBufferAvailable, length)

            // Handle wrap-around in ring buffer
            val firstChunkSize = minOf(bytesToCopy, ringBuffer.size - ringBufferReadPos)
            System.arraycopy(ringBuffer, ringBufferReadPos, target, offset, firstChunkSize)

            if (firstChunkSize < bytesToCopy) {
                // Need to wrap around and copy from beginning of ring buffer
                val secondChunkSize = bytesToCopy - firstChunkSize
                System.arraycopy(ringBuffer, 0, target, offset + firstChunkSize, secondChunkSize)
            }

            // Update ring buffer state
            ringBufferReadPos = (ringBufferReadPos + bytesToCopy) % ringBuffer.size
            ringBufferAvailable -= bytesToCopy

            // Notify ExoPlayer's statistics system about bytes transferred
            bytesTransferred(bytesToCopy)

            return bytesToCopy

        } catch (e: Exception) {
            Log.e(TAG, "Error reading audio data from Go player", e)
            throw IOException("Failed to read from Go player: ${e.message}", e)
        }
    }

    /**
     * Fills the ring buffer with data from the Go player.
     *
     * Reads from Go player and appends to ring buffer, handling wrap-around.
     * Called when ring buffer doesn't have enough data for the current request.
     */
    private fun fillRingBuffer() {
        // Calculate write position (after existing data)
        val writePos = (ringBufferReadPos + ringBufferAvailable) % ringBuffer.size

        // Read from Go player into temporary buffer
        // This call blocks until data is available (expected behavior)
        val bytesRead = goPlayer.readAudioData(buffer).toInt()

        if (bytesRead <= 0) {
            // No data available - could be timeout, disconnect, or end of stream
            Log.d(TAG, "readAudioData returned $bytesRead during fill")
            return
        }

        // Check if we have room in ring buffer
        val availableSpace = ringBuffer.size - ringBufferAvailable
        if (bytesRead > availableSpace) {
            // Ring buffer overflow - this shouldn't happen with proper sizing
            // Log warning but continue with what we can fit
            Log.w(TAG, "Ring buffer overflow: read $bytesRead, space $availableSpace")
        }

        val bytesToStore = minOf(bytesRead, availableSpace)

        // Copy to ring buffer, handling wrap-around
        val firstChunkSize = minOf(bytesToStore, ringBuffer.size - writePos)
        System.arraycopy(buffer, 0, ringBuffer, writePos, firstChunkSize)

        if (firstChunkSize < bytesToStore) {
            // Wrap around to beginning of ring buffer
            val secondChunkSize = bytesToStore - firstChunkSize
            System.arraycopy(buffer, firstChunkSize, ringBuffer, 0, secondChunkSize)
        }

        ringBufferAvailable += bytesToStore
    }

    /**
     * Returns the URI of the current stream.
     *
     * @return The stream URI if opened, null otherwise
     */
    override fun getUri(): Uri? {
        return if (opened) {
            dataSpec?.uri
        } else {
            null
        }
    }

    /**
     * Closes the data source and releases resources.
     *
     * Called by ExoPlayer when playback stops or source changes.
     * This method is idempotent - safe to call multiple times.
     *
     * Note: We do NOT clean up the Go player here - its lifecycle
     * is managed by the PlaybackService.
     */
    override fun close() {
        if (opened) {
            Log.d(TAG, "Closing GoPlayerDataSource")

            opened = false

            // Reset ring buffer state to prevent stale data on reopen
            ringBufferReadPos = 0
            ringBufferAvailable = 0

            // Notify ExoPlayer's transfer listeners that data transfer has ended
            // This completes the transfer lifecycle: started -> transferred -> ended
            try {
                transferEnded()
            } catch (e: Exception) {
                // Don't let listener errors prevent cleanup
                Log.w(TAG, "Error calling transferEnded", e)
            }

            // Clear the stored dataSpec
            dataSpec = null
        }
        // Safe to call multiple times - idempotent
    }
}
