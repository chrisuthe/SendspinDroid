package com.sendspindroid.sendspin.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

/**
 * Factory for creating audio decoders based on codec type.
 *
 * Provides methods to create decoders and check codec support on the device.
 */
object AudioDecoderFactory {

    private const val TAG = "AudioDecoderFactory"

    /**
     * Create a decoder for the specified codec.
     *
     * @param codec The codec identifier ("pcm", "flac", "opus")
     * @return An appropriate AudioDecoder implementation
     */
    fun create(codec: String): AudioDecoder {
        return when (codec.lowercase()) {
            "pcm" -> PcmDecoder()
            "flac" -> {
                if (isCodecSupported("flac")) {
                    FlacDecoder()
                } else {
                    Log.w(TAG, "FLAC not supported on this device, falling back to PCM")
                    PcmDecoder()
                }
            }
            "opus" -> {
                if (isCodecSupported("opus")) {
                    OpusDecoder()
                } else {
                    Log.w(TAG, "OPUS not supported on this device, falling back to PCM")
                    PcmDecoder()
                }
            }
            else -> {
                Log.w(TAG, "Unknown codec: $codec, falling back to PCM")
                PcmDecoder()
            }
        }
    }

    /**
     * Check if a codec is supported on this device.
     *
     * @param codec The codec identifier ("pcm", "flac", "opus")
     * @return true if the codec is supported
     */
    fun isCodecSupported(codec: String): Boolean {
        return when (codec.lowercase()) {
            "pcm" -> true  // Always supported
            "flac" -> isMediaCodecSupported(MediaFormat.MIMETYPE_AUDIO_FLAC)
            "opus" -> isMediaCodecSupported(MediaFormat.MIMETYPE_AUDIO_OPUS)
            else -> false
        }
    }

    /**
     * Get list of all supported codecs on this device.
     *
     * @return List of supported codec identifiers
     */
    fun getSupportedCodecs(): List<String> {
        return listOf("pcm", "flac", "opus").filter { isCodecSupported(it) }
    }

    private fun isMediaCodecSupported(mimeType: String): Boolean {
        return try {
            val codec = MediaCodec.createDecoderByType(mimeType)
            codec.release()
            true
        } catch (e: Exception) {
            Log.d(TAG, "MediaCodec not available for $mimeType: ${e.message}")
            false
        }
    }
}
