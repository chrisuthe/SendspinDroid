package com.sendspindroid.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Server list screen showing saved and discovered servers.
 * Groups servers into sections with headers.
 *
 * @param state Current server list state
 * @param onServerClick Called when a server is clicked to connect
 * @param onServerLongClick Called when a server is long-pressed (for edit menu)
 * @param onAddServerClick Called when the add server FAB is clicked
 * @param onEditServer Called when edit action is selected from menu
 * @param onDeleteServer Called when delete action is selected from menu
 * @param onSetAsDefault Called when set as default action is selected from menu
 * @param modifier Modifier for the screen
 */
@Composable
fun ServerListScreen(
    state: ServerListState,
    onServerClick: (ServerItem) -> Unit,
    onServerLongClick: (ServerItem) -> Unit,
    onAddServerClick: () -> Unit,
    onEditServer: (ServerItem) -> Unit,
    onDeleteServer: (ServerItem) -> Unit,
    onSetAsDefault: (ServerItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServerClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_server)
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (state.savedServers.isEmpty() && state.discoveredServers.isEmpty()) {
                // Empty state
                EmptyServerState(
                    isSearching = state.isSearching
                )
            } else {
                // Server list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    // Saved servers section
                    if (state.savedServers.isNotEmpty()) {
                        item(key = "header_saved") {
                            SectionHeader(
                                title = stringResource(R.string.saved_servers_section)
                            )
                        }
                        items(
                            items = state.savedServers,
                            key = { "saved_${it.id}" }
                        ) { server ->
                            ServerListItem(
                                server = server,
                                onClick = { onServerClick(server) },
                                onLongClick = { onServerLongClick(server) },
                                onEditServer = { onEditServer(server) },
                                onDeleteServer = { onDeleteServer(server) },
                                onSetAsDefault = { onSetAsDefault(server) }
                            )
                        }
                    }

                    // Discovered servers section
                    if (state.discoveredServers.isNotEmpty()) {
                        item(key = "header_discovered") {
                            SectionHeader(
                                title = stringResource(R.string.nearby_servers_section),
                                showProgress = state.isSearching
                            )
                        }
                        items(
                            items = state.discoveredServers,
                            key = { "discovered_${it.id}" }
                        ) { server ->
                            ServerListItem(
                                server = server,
                                onClick = { onServerClick(server) },
                                onLongClick = { onServerLongClick(server) },
                                onEditServer = null,
                                onDeleteServer = null,
                                onSetAsDefault = null
                            )
                        }
                    }

                    // Show searching indicator at bottom when actively searching
                    if (state.isSearching && state.discoveredServers.isEmpty()) {
                        item(key = "searching") {
                            SearchingIndicator()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Section header for server groups.
 */
@Composable
private fun SectionHeader(
    title: String,
    showProgress: Boolean = false,
    modifier: Modifier = Modifier
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
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        if (showProgress) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * Individual server list item with connection type indicator and actions.
 */
@Composable
private fun ServerListItem(
    server: ServerItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditServer: (() -> Unit)?,
    onDeleteServer: (() -> Unit)?,
    onSetAsDefault: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Server type indicator (icon)
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    server.isDefault -> MaterialTheme.colorScheme.primaryContainer
                    server.connectionType == ConnectionTypeUi.LOCAL -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Icon(
                    painter = painterResource(
                        when (server.connectionType) {
                            ConnectionTypeUi.LOCAL -> R.drawable.ic_wifi
                            ConnectionTypeUi.REMOTE -> R.drawable.ic_cloud_connected
                            ConnectionTypeUi.PROXY -> R.drawable.ic_vpn_key
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = when {
                        server.isDefault -> MaterialTheme.colorScheme.onPrimaryContainer
                        server.connectionType == ConnectionTypeUi.LOCAL -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Server info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (server.isDefault) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = stringResource(R.string.default_server),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Server address or status
                Text(
                    text = server.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Actions menu (for saved servers only)
            if (onEditServer != null || onDeleteServer != null || onSetAsDefault != null) {
                Box {
                    IconButton(
                        onClick = { showMenu = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        onEditServer?.let {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_server)) },
                                onClick = {
                                    showMenu = false
                                    it()
                                }
                            )
                        }
                        onSetAsDefault?.let {
                            if (!server.isDefault) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.set_as_default)) },
                                    onClick = {
                                        showMenu = false
                                        it()
                                    }
                                )
                            }
                        }
                        onDeleteServer?.let {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.delete_server),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    it()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state when no servers are available.
 */
@Composable
private fun EmptyServerState(
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_server),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.4f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.no_servers_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.searching_for_servers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            if (isSearching) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Searching indicator shown at the bottom of the list.
 */
@Composable
private fun SearchingIndicator(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.searching_for_servers),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// State classes
// ============================================================================

/**
 * State holder for ServerListScreen.
 */
data class ServerListState(
    val savedServers: List<ServerItem> = emptyList(),
    val discoveredServers: List<ServerItem> = emptyList(),
    val isSearching: Boolean = false
)

/**
 * Individual server item data.
 */
data class ServerItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val connectionType: ConnectionTypeUi,
    val isDefault: Boolean = false
)

/**
 * Connection type for UI display.
 */
enum class ConnectionTypeUi {
    LOCAL,   // Direct LAN connection via mDNS
    REMOTE,  // Remote connection via WAN IP
    PROXY    // Connection via proxy server
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun ServerListScreenPreview() {
    SendSpinTheme {
        ServerListScreen(
            state = ServerListState(
                savedServers = listOf(
                    ServerItem(
                        id = "1",
                        name = "Living Room",
                        subtitle = "192.168.1.100:8000",
                        connectionType = ConnectionTypeUi.LOCAL,
                        isDefault = true
                    ),
                    ServerItem(
                        id = "2",
                        name = "Office",
                        subtitle = "Remote connection",
                        connectionType = ConnectionTypeUi.REMOTE
                    )
                ),
                discoveredServers = listOf(
                    ServerItem(
                        id = "3",
                        name = "Kitchen Speaker",
                        subtitle = "192.168.1.101:8000",
                        connectionType = ConnectionTypeUi.LOCAL
                    )
                ),
                isSearching = true
            ),
            onServerClick = {},
            onServerLongClick = {},
            onAddServerClick = {},
            onEditServer = {},
            onDeleteServer = {},
            onSetAsDefault = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerListEmptyPreview() {
    SendSpinTheme {
        ServerListScreen(
            state = ServerListState(
                isSearching = true
            ),
            onServerClick = {},
            onServerLongClick = {},
            onAddServerClick = {},
            onEditServer = {},
            onDeleteServer = {},
            onSetAsDefault = {}
        )
    }
}
