package com.sendspindroid.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Paints a pairing token as a scannable QR code.
 *
 * Two rendering decisions here are load-bearing, and both fail silently when
 * they are wrong - the operator sees a QR that simply never scans, with nothing
 * on screen suggesting why:
 *
 * 1. **The colors are hardcoded black on white, never [androidx.compose.material3.MaterialTheme].**
 *    In dark theme a theme-derived QR comes out light-on-dark or low-contrast,
 *    and many readers will not invert.
 * 2. **[FilterQuality.None].** The matrix is one pixel per module, so it is
 *    scaled up by a large factor. Default bilinear filtering smears the module
 *    edges into grey; a reader needs the hard black/white transitions.
 *
 * The white background extends past the symbol via [Modifier.padding] inside
 * [Modifier.background] so the quiet zone stays white in dark theme too.
 */
@Composable
fun PairingQrImage(
    token: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    // Keyed on the token: encoding is cheap, but recomposition is frequent and
    // this allocates a bitmap.
    val bitmap = remember(token) {
        val matrix = PairingQrCode.encode(token)
        val pixels = IntArray(matrix.size * matrix.size)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                pixels[y * matrix.size + x] =
                    if (matrix.isDark(x, y)) BLACK else WHITE
            }
        }
        Bitmap.createBitmap(pixels, matrix.size, matrix.size, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        filterQuality = FilterQuality.None,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .background(Color.White)
            .padding(8.dp)
            .size(size),
    )
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
