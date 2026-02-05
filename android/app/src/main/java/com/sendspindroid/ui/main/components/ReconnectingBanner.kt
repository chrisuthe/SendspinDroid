package com.sendspindroid.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.main.ReconnectingState
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Banner displayed when reconnecting to a server.
 * Shows attempt number and remaining buffer time.
 */
@Composable
fun ReconnectingBanner(
    state: ReconnectingState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )

        Spacer(modifier = Modifier.width(12.dp))

        val bufferText = if (state.bufferSeconds > 0) {
            stringResource(
                R.string.reconnecting_with_buffer,
                state.serverName,
                state.attempt,
                state.bufferSeconds
            )
        } else {
            stringResource(
                R.string.reconnecting_attempt,
                state.serverName,
                state.attempt
            )
        }

        Text(
            text = bufferText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReconnectingBannerPreview() {
    SendSpinTheme {
        ReconnectingBanner(
            state = ReconnectingState(
                serverName = "Living Room",
                attempt = 2,
                bufferMs = 15000
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReconnectingBannerNoBufferPreview() {
    SendSpinTheme {
        ReconnectingBanner(
            state = ReconnectingState(
                serverName = "Kitchen",
                attempt = 1,
                bufferMs = 0
            )
        )
    }
}
