package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A full-width "Add to Queue" outlined button for album/artist detail screens.
 *
 * Positioned below the ActionRow as an additional action for adding
 * the entire album or artist's tracks to the play queue.
 * Styled to match the Shuffle / Add to Playlist buttons in ActionRow.
 *
 * @param onClick Called when the button is tapped
 * @param modifier Optional modifier
 */
@Composable
fun AddToQueueButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(text = "Add to Queue")
    }
}
