package com.sendspindroid.ui.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.sendspindroid.sendspin.pairing.PairingToken
import com.google.zxing.LuminanceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing a pairing QR has to get right: a scan and a copy/paste must
 * yield the same string.
 *
 * `pairing.md#pairing-token`: "A QR code carries the token string verbatim, with
 * no URI scheme or wrapper, so a scan and a copy/paste yield identical input."
 *
 * So these tests do not inspect the payload we handed the encoder - that would
 * only prove we passed what we passed. They run the matrix back through a real
 * QR *reader* and compare against the token, which is what a phone camera will
 * actually recover.
 */
class PairingQrCodeTest {

    /** The spec reference vector: client_key 0x00..0x1f, pairing_psk 0xe0..0xff. */
    private val referenceToken = PairingToken.encode(
        clientKey = ByteArray(32) { it.toByte() },
        pairingPsk = ByteArray(32) { (0xe0 + it).toByte() },
    )

    @Test
    fun `a scan of the matrix recovers the token exactly`() {
        val matrix = PairingQrCode.encode(referenceToken)

        assertEquals(referenceToken, scan(matrix))
    }

    @Test
    fun `the matrix is a valid square QR symbol`() {
        val matrix = PairingQrCode.encode(referenceToken)

        // Every QR version is 4v+17 modules square, plus the quiet zone on both
        // sides. A grid that fails this is not a symbol any reader will accept.
        assertTrue("not square: ${matrix.size}", matrix.size > 0)
        val symbol = matrix.size - 2 * PairingQrCode.QUIET_ZONE_MODULES
        assertTrue("$symbol is not 4v+17", symbol in 21..177 && (symbol - 17) % 4 == 0)
    }

    @Test
    fun `the matrix carries a quiet zone`() {
        val matrix = PairingQrCode.encode(referenceToken)

        // A symbol drawn flush to its edge scans erratically, and the failure
        // looks to an operator like "nothing happens" rather than like a bug.
        val last = matrix.size - 1
        for (i in 0 until matrix.size) {
            assertTrue("dark module on the top edge at $i", !matrix.isDark(i, 0))
            assertTrue("dark module on the bottom edge at $i", !matrix.isDark(i, last))
            assertTrue("dark module on the left edge at $i", !matrix.isDark(0, i))
            assertTrue("dark module on the right edge at $i", !matrix.isDark(last, i))
        }
    }

    /** Decodes the matrix the way a camera would, via a real QR reader. */
    private fun scan(matrix: PairingQrCode.Matrix): String {
        val bits = BitMatrix(matrix.size, matrix.size)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) bits.set(x, y)
            }
        }
        val source = object : LuminanceSource(matrix.size, matrix.size) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val out = row?.takeIf { it.size >= width } ?: ByteArray(width)
                for (x in 0 until width) out[x] = if (bits.get(x, y)) 0 else 0xFF.toByte()
                return out
            }

            override fun getMatrix(): ByteArray {
                val out = ByteArray(width * height)
                for (y in 0 until height) getRow(y, ByteArray(width)).copyInto(out, y * width)
                return out
            }
        }
        val result = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )
        return result.text
    }
}
