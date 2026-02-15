package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * MA path — asks whether the user is on the same network as their server.
 * Shows an auto-detected network hint (e.g. "You appear to be on WiFi").
 * Tapping a card navigates directly (no Next button).
 */
@Composable
fun NetworkQuestionStep(
    networkHint: String,
    onSameNetwork: () -> Unit,
    onRemote: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Where\u2019s Your Server?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Are you on the same WiFi network as your Music Assistant server right now?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (networkHint.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            AssistChip(
                onClick = {},
                label = { Text(networkHint) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        NetworkOptionCard(
            icon = R.drawable.ic_wifi,
            title = "Yes, same network",
            description = "I\u2019m on the same WiFi or wired network. Let\u2019s find the server automatically.",
            onClick = onSameNetwork
        )

        Spacer(modifier = Modifier.height(16.dp))

        NetworkOptionCard(
            icon = R.drawable.ic_cloud_connected,
            title = "No, I\u2019m remote",
            description = "I\u2019m away from home or on mobile data. I\u2019ll connect using a Remote ID or proxy URL.",
            onClick = onRemote
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkOptionCard(
    icon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NetworkQuestionStepPreview() {
    SendSpinTheme {
        NetworkQuestionStep(
            networkHint = "You appear to be on WiFi",
            onSameNetwork = {},
            onRemote = {}
        )
    }
}
