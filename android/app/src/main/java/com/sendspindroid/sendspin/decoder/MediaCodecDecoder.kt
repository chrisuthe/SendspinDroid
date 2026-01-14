package com.sendspindroid.sendspin.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Base class for MediaCodec-based audio decoders.
 *
 * Provides synchronous decoding using Android's MediaCodec API.
 * Subclasses implement codec-specific format configuration.
 */
abstract class MediaCodecDecoder(
    protected val mimeType: String
) : AudioDecoder {

    companion object {
        private const val TAG = "MediaCodecDecoder"
        private const val TIMEOUT_US = 10_000L  // 10ms timeout for buffer operations
    }

    protected var mediaCodec: MediaCodec? = null
    protected var outputFormat: MediaFormat? = null
    private var _isConfigured = false

    override val isConfigured: Boolean
        get() = _isConfigured

    override fun configure(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    ) {
        try {
            // Create base MediaFormat
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels)

            // Apply codec-specific configuration (template method)
            configureFormat(format, sampleRate, channels, bitDepth, codecHeader)

            // Create and configure decoder
            mediaCodec = MediaCodec.createDecoderByType(mimeType)
            mediaCodec?.configure(format, null, null, 0)
            mediaCodec?.start()

            _isConfigured = true
            Log.d(TAG, "Decoder configured: $mimeType, ${sampleRate}Hz, ${channels}ch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder", e)
            release()
            throw e
        }
    }

    /**
     * Template method for codec-specific format configuration.
     *
     * Subclasses override this to set codec-specific parameters like
     * CSD (Codec Specific Data) buffers.
     */
    protected abstract fun configureFormat(
        format: MediaFormat,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    )

    override fun decode(compressedData: ByteArray): ByteArray {
        val codec = mediaCodec
            ?: throw IllegalStateException("Decoder not configured")

        val outputBuffer = ByteArrayOutputStream()

        // Feed input to decoder
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(compressedData)
                codec.queueInputBuffer(inputIndex, 0, compressedData.size, 0, 0)
            }
        } else {
            Log.w(TAG, "No input buffer available")
        }

        // Drain output from decoder
        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

        while (outputIndex >= 0) {
            val outBuffer = codec.getOutputBuffer(outputIndex)
            if (outBuffer != null && bufferInfo.size > 0) {
                val pcmData = ByteArray(bufferInfo.size)
                outBuffer.position(bufferInfo.offset)
                outBuffer.get(pcmData, 0, bufferInfo.size)
                outputBuffer.write(pcmData)
            }

            codec.releaseOutputBuffer(outputIndex, false)
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)  // Non-blocking drain
        }

        // Handle format change
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            outputFormat = codec.outputFormat
            Log.d(TAG, "Output format changed: $outputFormat")
        }

        return outputBuffer.toByteArray()
    }

    override fun flush() {
        try {
            mediaCodec?.flush()
            // MediaCodec requires start() after flush() in synchronous mode
            mediaCodec?.start()
            Log.d(TAG, "Decoder flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing decoder", e)
        }
    }

    override fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        } finally {
            mediaCodec = null
            _isConfigured = false
            Log.d(TAG, "Decoder released")
        }
    }
}
