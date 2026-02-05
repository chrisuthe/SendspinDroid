package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Section header for the server list (e.g., "Saved Servers", "Nearby Servers").
 */
@Composable
fun ServerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    showScanning: Boolean = false,
    emptyHint: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )

        if (showScanning) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Empty hint (shows when section has no servers)
        if (emptyHint != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerSectionHeaderPreview() {
    SendSpinTheme {
        ServerSectionHeader(title = "Saved Servers")
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerSectionHeaderScanningPreview() {
    SendSpinTheme {
        ServerSectionHeader(
            title = "Nearby Servers",
            showScanning = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerSectionHeaderEmptyPreview() {
    SendSpinTheme {
        ServerSectionHeader(
            title = "Nearby Servers",
            showScanning = true,
            emptyHint = "Searching..."
        )
    }
}
