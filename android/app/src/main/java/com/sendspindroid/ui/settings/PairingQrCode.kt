package com.sendspindroid.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a pairing token as a QR matrix.
 *
 * `pairing.md#pairing-token`: "A QR code carries the token string verbatim, with
 * no URI scheme or wrapper, so a scan and a copy/paste yield identical input."
 *
 * That sentence is the whole contract, and it is easy to break by accident: a
 * `sendspin://` scheme, a trailing newline, or the display formatting leaking
 * into the payload all produce a QR that scans to *something*, just not the
 * thing the operator pasted. Nothing about the failure looks like a bug - the
 * server simply says the token is invalid. So the payload is the token string
 * and nothing else, and `PairingQrCodeTest` scans the output back to prove it.
 *
 * Kept free of Compose and Android types on purpose: this is the part with a
 * correctness requirement, so it runs in a plain JVM unit test. Painting the
 * grid lives in [PairingQrImage].
 */
object PairingQrCode {

    /**
     * Modules of light border on each side. QR readers need a quiet zone to
     * find the symbol; the spec's minimum is 4, but 2 is reliable for a screen
     * scanned at close range and keeps the symbol larger on a phone-sized card.
     */
    const val QUIET_ZONE_MODULES = 2

    /** A square grid of light/dark modules, quiet zone included. */
    class Matrix internal constructor(
        val size: Int,
        private val dark: BooleanArray,
    ) {
        fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
    }

    fun encode(token: String): Matrix {
        // The token is drawn from [0-9A-Z:], exactly the QR alphanumeric set,
        // so zxing packs it at 5.5 bits per character instead of 8. That is
        // what keeps 107 characters down to a symbol that stays scannable at
        // the size a settings screen can spare.
        val bits = QRCodeWriter().encode(
            token,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )

        // Width 0 asks zxing for the natural, one-pixel-per-module symbol.
        // Scaling is the renderer's job - blowing it up here would only make a
        // bigger array to copy.
        val size = bits.width
        val dark = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                dark[y * size + x] = bits.get(x, y)
            }
        }
        return Matrix(size, dark)
    }
}
