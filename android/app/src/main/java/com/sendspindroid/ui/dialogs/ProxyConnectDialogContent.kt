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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Proxy Connect dialog content for connecting via authenticated reverse proxy.
 *
 * Supports two authentication modes:
 * - Login mode: Enter username/password
 * - Token mode: Paste auth token directly
 *
 * @param savedServers List of previously saved proxy servers
 * @param isLoading Whether a connection attempt is in progress
 * @param onConnect Called when user confirms connection
 * @param onDeleteSavedServer Called when user wants to delete a saved server
 * @param onDismiss Called when dialog is dismissed
 */
@Composable
fun ProxyConnectDialog(
    savedServers: List<SavedProxyServer>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onConnect: (url: String, authMode: ProxyAuthModeDialog, credentials: ProxyCredentials) -> Unit,
    onDeleteSavedServer: (SavedProxyServer) -> Unit,
    onDismiss: () -> Unit
) {
    var proxyUrl by remember { mutableStateOf("") }
    var authMode by remember { mutableIntStateOf(0) } // 0 = Login, 1 = Token
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.proxy_access)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Proxy URL input
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = { proxyUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.proxy_url)) },
                    placeholder = { Text(stringResource(R.string.proxy_url_hint)) },
                    singleLine = true,
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_vpn_key),
                            contentDescription = null
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Auth mode tabs
                TabRow(selectedTabIndex = authMode) {
                    Tab(
                        selected = authMode == 0,
                        onClick = { authMode = 0 },
                        text = { Text(stringResource(R.string.proxy_auth_login)) },
                        enabled = !isLoading
                    )
                    Tab(
                        selected = authMode == 1,
                        onClick = { authMode = 1 },
                        text = { Text(stringResource(R.string.proxy_auth_token)) },
                        enabled = !isLoading
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Auth fields based on mode
                when (authMode) {
                    0 -> {
                        // Login mode
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.proxy_username)) },
                            singleLine = true,
                            enabled = !isLoading,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_person),
                                    contentDescription = null
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.proxy_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = !isLoading,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                    1 -> {
                        // Token mode
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.proxy_token)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !isLoading,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Nickname input (optional)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.proxy_server_nickname)) },
                    placeholder = { Text(stringResource(R.string.proxy_server_nickname_hint)) },
                    singleLine = true,
                    enabled = !isLoading
                )

                // Saved servers section
                if (savedServers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.saved_proxy_servers),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.height(120.dp)
                    ) {
                        items(
                            items = savedServers,
                            key = { it.id }
                        ) { server ->
                            SavedProxyServerItem(
                                server = server,
                                onClick = {
                                    proxyUrl = server.url
                                    nickname = server.nickname
                                    if (server.username.isNotEmpty()) {
                                        authMode = 0
                                        username = server.username
                                    } else {
                                        authMode = 1
                                    }
                                },
                                onDelete = { onDeleteSavedServer(server) },
                                enabled = !isLoading
                            )
                        }
                    }
                }

                // Inline error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val credentials = if (authMode == 0) {
                        ProxyCredentials.Login(username, password, nickname.takeIf { it.isNotBlank() })
                    } else {
                        ProxyCredentials.Token(token, nickname.takeIf { it.isNotBlank() })
                    }
                    onConnect(
                        proxyUrl,
                        if (authMode == 0) ProxyAuthModeDialog.LOGIN else ProxyAuthModeDialog.TOKEN,
                        credentials
                    )
                },
                enabled = !isLoading && proxyUrl.isNotBlank() && (
                    (authMode == 0 && username.isNotBlank() && password.isNotBlank()) ||
                    (authMode == 1 && token.isNotBlank())
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    )
}

@Composable
private fun SavedProxyServerItem(
    server: SavedProxyServer,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = enabled, onClick = onClick),
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
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_vpn_key),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
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
                    text = server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
                enabled = enabled
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
 * Proxy authentication mode.
 */
enum class ProxyAuthModeDialog {
    LOGIN, TOKEN
}

/**
 * Proxy credentials data.
 */
sealed class ProxyCredentials {
    data class Login(val username: String, val password: String, val nickname: String?) : ProxyCredentials()
    data class Token(val token: String, val nickname: String?) : ProxyCredentials()
}

/**
 * Data class for saved proxy server.
 */
data class SavedProxyServer(
    val id: String,
    val url: String,
    val nickname: String,
    val username: String = ""
)

// ============================================================================
// Previews
// ============================================================================

@Preview
@Composable
private fun ProxyConnectDialogPreview() {
    SendSpinTheme {
        ProxyConnectDialog(
            savedServers = listOf(
                SavedProxyServer("1", "https://proxy.example.com/music", "Home Proxy", "user@example.com"),
                SavedProxyServer("2", "https://office.example.com/ma", "Office")
            ),
            isLoading = false,
            onConnect = { _, _, _ -> },
            onDeleteSavedServer = {},
            onDismiss = {}
        )
    }
}

@Preview
@Composable
private fun ProxyConnectDialogLoadingPreview() {
    SendSpinTheme {
        ProxyConnectDialog(
            savedServers = emptyList(),
            isLoading = true,
            onConnect = { _, _, _ -> },
            onDeleteSavedServer = {},
            onDismiss = {}
        )
    }
}
