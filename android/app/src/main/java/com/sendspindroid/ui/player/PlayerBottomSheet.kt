package com.sendspindroid.ui.player

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Bottom sheet for managing speaker group membership.
 *
 * Shows the current (locked) player and a list of compatible speakers
 * with toggles to add/remove them from the group.
 *
 * Follows the same pattern as [com.sendspindroid.ui.queue.QueueBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerBottomSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        PlayerSheetContent(viewModel = viewModel)
    }
}
