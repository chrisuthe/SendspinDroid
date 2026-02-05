package com.sendspindroid.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Remote Connect dialog content for connecting via Music Assistant Remote Access ID.
 *
 * @param savedServers List of previously saved remote servers
 * @param initialRemoteId Initial value for remote ID field (e.g., from QR scan)
 * @param onConnect Called when user confirms connection with remote ID and optional nickname
 * @param onScanQr Called when user taps QR code scanner button
 * @param onDeleteSavedServer Called when user wants to delete a saved server
 * @param onDismiss Called when dialog is dismissed
 */
@Composable
fun RemoteConnectDialog(
    savedServers: List<SavedRemoteServer>,
    initialRemoteId: String = "",
    onConnect: (remoteId: String, nickname: String?) -> Unit,
    onScanQr: () -> Unit,
    onDeleteSavedServer: (SavedRemoteServer) -> Unit,
    onDismiss: () -> Unit
) {
    var remoteId by remember(initialRemoteId) { mutableStateOf(initialRemoteId) }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_access)) },
        text = {
            Column {
                // Remote ID input
                OutlinedTextField(
                    value = remoteId,
                    onValueChange = { remoteId = it.uppercase().take(26) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote_id)) },
                    placeholder = { Text(stringResource(R.string.remote_id_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = onScanQr) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.scan_qr)
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Nickname input (optional)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.wizard_name_hint)) },
                    singleLine = true
                )

                // Saved servers section
                if (savedServers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.saved_servers_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.height(150.dp)
                    ) {
                        items(
                            items = savedServers,
                            key = { it.id }
                        ) { server ->
                            SavedRemoteServerItem(
                                server = server,
                                onClick = {
                                    remoteId = server.remoteId
                                    nickname = server.nickname
                                },
                                onDelete = { onDeleteSavedServer(server) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConnect(remoteId, nickname.takeIf { it.isNotBlank() })
                },
                enabled = remoteId.length == 26
            ) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    )
}

@Composable
private fun SavedRemoteServerItem(
    server: SavedRemoteServer,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud_connected),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Server info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.nickname,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.remoteId.take(6)}...${server.remoteId.takeLast(4)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Data class for saved remote server.
 */
data class SavedRemoteServer(
    val id: String,
    val remoteId: String,
    val nickname: String
)

// ============================================================================
// Previews
// ============================================================================

@Preview
@Composable
private fun RemoteConnectDialogPreview() {
    SendSpinTheme {
        RemoteConnectDialog(
            savedServers = listOf(
                SavedRemoteServer("1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "Home Server"),
                SavedRemoteServer("2", "12345678901234567890123456", "Office")
            ),
            onConnect = { _, _ -> },
            onScanQr = {},
            onDeleteSavedServer = {},
            onDismiss = {}
        )
    }
}
