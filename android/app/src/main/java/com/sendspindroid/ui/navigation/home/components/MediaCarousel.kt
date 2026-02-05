package com.sendspindroid.ui.navigation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.navigation.home.HomeViewModel.SectionState
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Horizontal carousel section for the home screen.
 *
 * Displays a section title and horizontally scrolling list of media cards.
 * Handles loading, success, error, and empty states.
 *
 * @param title Section title displayed above the carousel
 * @param state Current state of the section data
 * @param onItemClick Called when a card is tapped
 * @param modifier Modifier for the section
 */
@Composable
fun MediaCarousel(
    title: String,
    state: SectionState<MaLibraryItem>,
    onItemClick: (MaLibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        // Section title
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.02).sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        when (state) {
            is SectionState.Loading -> {
                LoadingIndicator()
            }

            is SectionState.Success -> {
                if (state.items.isEmpty()) {
                    EmptyState()
                } else {
                    CarouselContent(
                        items = state.items,
                        onItemClick = onItemClick
                    )
                }
            }

            is SectionState.Error -> {
                EmptyState(message = "Failed to load")
            }
        }
    }
}

@Composable
private fun CarouselContent(
    items: List<MaLibraryItem>,
    onItemClick: (MaLibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = items,
            key = { "${it.mediaType}_${it.id}" }
        ) { item ->
            MediaCard(
                item = item,
                onClick = { onItemClick(item) }
            )
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    message: String = "No items"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaCarouselLoadingPreview() {
    SendSpinTheme {
        MediaCarousel(
            title = "Recently Played",
            state = SectionState.Loading,
            onItemClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaCarouselSuccessPreview() {
    SendSpinTheme {
        MediaCarousel(
            title = "Recently Played",
            state = SectionState.Success(
                listOf(
                    MaTrack("1", "Track One", "Artist A", "Album 1", null, null, 225),
                    MaTrack("2", "Track Two", "Artist B", "Album 2", null, null, 180),
                    MaTrack("3", "Track Three", "Artist C", "Album 3", null, null, 312)
                )
            ),
            onItemClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaCarouselEmptyPreview() {
    SendSpinTheme {
        MediaCarousel(
            title = "Playlists",
            state = SectionState.Success(emptyList()),
            onItemClick = {}
        )
    }
}
