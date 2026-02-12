package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sendspindroid.R
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Album art card with optional buffering overlay.
 * Displays artwork from various sources (byte array, URI, or URL).
 */
@Composable
fun AlbumArtCard(
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    contentDescription: String = stringResource(R.string.album_art)
) {
    Card(
        modifier = modifier
            .widthIn(max = maxWidth)
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Album Art Image
            val context = LocalContext.current
            val imageRequest = when (artworkSource) {
                is ArtworkSource.ByteArray -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.data)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Uri -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.uri)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Url -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.url)
                        .crossfade(true)
                        .build()
                }
                null -> null
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.placeholder_album_simple),
                error = painterResource(R.drawable.placeholder_album_simple),
                fallback = painterResource(R.drawable.placeholder_album_simple)
            )

            // Buffering Overlay
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumArtCardPreview() {
    SendSpinTheme {
        AlbumArtCard(
            artworkSource = null,
            isBuffering = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumArtCardBufferingPreview() {
    SendSpinTheme {
        AlbumArtCard(
            artworkSource = null,
            isBuffering = true
        )
    }
}
