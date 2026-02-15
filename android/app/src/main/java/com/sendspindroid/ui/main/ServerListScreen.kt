package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.main.components.ServerItemStatus
import com.sendspindroid.ui.main.components.ServerListEmptyState
import com.sendspindroid.ui.main.components.ServerListItem
import com.sendspindroid.ui.main.components.ServerSectionHeader
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Server list screen showing saved and discovered servers in sections.
 *
 * When no saved servers exist, shows the welcome/hero empty state with
 * discovered servers inline for quick-connect. When saved servers exist,
 * shows the standard sectioned list with a FAB.
 */
@Composable
fun ServerListScreen(
    savedServers: StateFlow<List<UnifiedServer>>,
    discoveredServers: StateFlow<List<UnifiedServer>>,
    onlineSavedServerIds: StateFlow<Set<String>>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>, // serverId -> (attempt, maxAttempts)
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val saved by savedServers.collectAsState()
    val discovered by discoveredServers.collectAsState()
    val onlineIds by onlineSavedServerIds.collectAsState()

    ServerListScreenContent(
        savedServers = saved,
        discoveredServers = discovered,
        onlineSavedServerIds = onlineIds,
        isScanning = isScanning,
        serverStatuses = serverStatuses,
        reconnectInfo = reconnectInfo,
        onServerClick = onServerClick,
        onServerLongClick = onServerLongClick,
        onQuickConnectClick = onQuickConnectClick,
        onAddServerClick = onAddServerClick,
        modifier = modifier
    )
}

/**
 * Stateless version of ServerListScreen for simpler integration.
 */
@Composable
fun ServerListScreen(
    savedServers: List<UnifiedServer>,
    discoveredServers: List<UnifiedServer>,
    onlineSavedServerIds: Set<String>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>,
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ServerListScreenContent(
        savedServers = savedServers,
        discoveredServers = discoveredServers,
        onlineSavedServerIds = onlineSavedServerIds,
        isScanning = isScanning,
        serverStatuses = serverStatuses,
        reconnectInfo = reconnectInfo,
        onServerClick = onServerClick,
        onServerLongClick = onServerLongClick,
        onQuickConnectClick = onQuickConnectClick,
        onAddServerClick = onAddServerClick,
        modifier = modifier
    )
}

/**
 * Shared implementation for both overloads.
 */
@Composable
private fun ServerListScreenContent(
    savedServers: List<UnifiedServer>,
    discoveredServers: List<UnifiedServer>,
    onlineSavedServerIds: Set<String>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>,
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSavedServers = savedServers.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        if (!hasSavedServers) {
            // Welcome / empty state — hero card with discovered servers inline
            ServerListEmptyState(
                isScanning = isScanning,
                discoveredServers = discoveredServers,
                onAddServerClick = onAddServerClick,
                onQuickConnectClick = onQuickConnectClick
            )
        } else {
            // Normal server list with saved + discovered sections
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp) // Space for FAB
            ) {
                // Saved Servers Section
                item(key = "header_saved") {
                    ServerSectionHeader(
                        title = stringResource(R.string.saved_servers_section)
                    )
                }

                items(
                    items = savedServers,
                    key = { "saved_${it.id}" }
                ) { server ->
                    val status = serverStatuses[server.id]
                        ?: if (onlineSavedServerIds.contains(server.id)) ServerItemStatus.ONLINE
                        else ServerItemStatus.DISCONNECTED
                    val reconnect = reconnectInfo[server.id]

                    ServerListItem(
                        server = server,
                        status = status,
                        onClick = { onServerClick(server) },
                        onLongClick = { onServerLongClick(server) },
                        reconnectAttempt = reconnect?.first,
                        reconnectMaxAttempts = reconnect?.second
                    )
                }

                // Discovered Servers Section
                item(key = "header_discovered") {
                    ServerSectionHeader(
                        title = stringResource(R.string.nearby_servers_section),
                        showScanning = isScanning,
                        emptyHint = if (discoveredServers.isEmpty() && isScanning)
                            stringResource(R.string.scanning_ellipsis)
                        else null
                    )
                }

                if (discoveredServers.isNotEmpty()) {
                    items(
                        items = discoveredServers,
                        key = { "discovered_${it.id}" }
                    ) { server ->
                        val status = serverStatuses[server.id] ?: ServerItemStatus.ONLINE

                        ServerListItem(
                            server = server,
                            status = status,
                            onClick = { onServerClick(server) },
                            onLongClick = { onServerLongClick(server) },
                            onQuickConnectClick = { onQuickConnectClick(server) }
                        )
                    }
                }
            }

            // FAB for adding servers — only when saved servers exist
            // (empty state has its own prominent Add Server button)
            FloatingActionButton(
                onClick = onAddServerClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.add_server)
                )
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    remote = RemoteConnection("ABCDE12345FGHIJ67890KLMNO1"),
                    isDefaultServer = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Bedroom",
                    lastConnectedMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.101:8927")
                )
            ),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "3",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.102:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = setOf("1"),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenEmptyPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = emptyList(),
            discoveredServers = emptyList(),
            onlineSavedServerIds = emptySet(),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenEmptyWithDiscoveredPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = emptyList(),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    local = LocalConnection("192.168.1.100:8927"),
                    isDiscovered = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.101:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = emptySet(),
            isScanning = false,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenConnectingPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    isDefaultServer = true
                )
            ),
            discoveredServers = emptyList(),
            onlineSavedServerIds = setOf("1"),
            isScanning = false,
            serverStatuses = mapOf("1" to ServerItemStatus.CONNECTING),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {}
        )
    }
}

// -- Multi-Device Previews --

@AllDevicePreviews
@Composable
private fun ServerListScreenAllDevicesPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    remote = RemoteConnection("ABCDE12345FGHIJ67890KLMNO1"),
                    isDefaultServer = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Bedroom",
                    lastConnectedMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.101:8927")
                ),
                UnifiedServer(
                    id = "4",
                    name = "Office",
                    lastConnectedMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000,
                    remote = RemoteConnection("XYZAB12345CDEFG67890HIJKL1")
                )
            ),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "3",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.102:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = setOf("1"),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {}
        )
    }
}
