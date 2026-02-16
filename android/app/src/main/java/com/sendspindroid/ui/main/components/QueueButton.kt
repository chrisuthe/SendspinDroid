package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Queue button shown on the NowPlayingScreen.
 *
 * On phones, opens the queue bottom sheet (no active state).
 * On tablets, toggles the inline queue sidebar (uses [isActive] for visual feedback).
 *
 * @param onClick Called when the button is tapped
 * @param isActive When true, renders as a filled button to indicate the queue is visible
 * @param modifier Optional modifier
 */
@Composable
fun QueueButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false
) {
    if (isActive) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_queue_music),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.queue_view))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_queue_music),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.queue_view))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QueueButtonPreview() {
    SendSpinTheme {
        QueueButton(onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun QueueButtonActivePreview() {
    SendSpinTheme {
        QueueButton(onClick = {}, isActive = true)
    }
}
