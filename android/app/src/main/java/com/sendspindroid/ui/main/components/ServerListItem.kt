package com.sendspindroid.ui.main.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Server status for UI display.
 */
enum class ServerItemStatus {
    DISCONNECTED,
    ONLINE,      // Server discovered on network but not connected
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * A server list item card showing server info and connection methods.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerListItem(
    server: UnifiedServer,
    status: ServerItemStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onQuickConnectClick: (() -> Unit)? = null,
    reconnectAttempt: Int? = null,
    reconnectMaxAttempts: Int? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .tvFocusable()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top row: Status indicator, Name, Default star, Connection icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator dot
                StatusIndicator(status = status)

                Spacer(modifier = Modifier.width(12.dp))

                // Server name
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Default server star
                if (server.isDefaultServer) {
                    Icon(
                        painter = painterResource(R.drawable.ic_star),
                        contentDescription = stringResource(R.string.accessibility_default_server),
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Connection method icons
                ConnectionMethodIcons(server = server)
            }

            Spacer(modifier = Modifier.size(4.dp))

            // Bottom row: Subtitle and Quick Connect chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Subtitle (status text or last connected)
                val subtitleText = when {
                    status == ServerItemStatus.CONNECTING -> stringResource(R.string.connecting)
                    status == ServerItemStatus.RECONNECTING && reconnectAttempt != null && reconnectMaxAttempts != null ->
                        stringResource(R.string.reconnecting_progress, reconnectAttempt, reconnectMaxAttempts)
                    status == ServerItemStatus.CONNECTED -> stringResource(R.string.connected)
                    server.isDiscovered -> server.local?.address ?: ""
                    else -> server.formattedLastConnected
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 24.dp) // Align with server name (after status dot)
                )

                // Quick Connect chip (for discovered servers)
                if (server.isDiscovered && onQuickConnectClick != null && status != ServerItemStatus.CONNECTING) {
                    AssistChip(
                        onClick = onQuickConnectClick,
                        label = { Text(stringResource(R.string.quick_connect)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = null
                    )
                }
            }
        }
    }
}

/**
 * Status indicator dot showing server connection status.
 */
@Composable
private fun StatusIndicator(
    status: ServerItemStatus,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        ServerItemStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
        ServerItemStatus.ONLINE -> Color(0xFF4CAF50) // Green
        ServerItemStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ServerItemStatus.CONNECTED -> Color(0xFF4CAF50) // Green
        ServerItemStatus.RECONNECTING -> Color(0xFFFF9800) // Orange
        ServerItemStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier.size(12.dp),
        shape = CircleShape,
        color = color
    ) {}
}

/**
 * Connection method icons (WiFi, Cloud, VPN).
 */
@Composable
private fun ConnectionMethodIcons(
    server: UnifiedServer,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (server.local != null) {
            Icon(
                painter = painterResource(R.drawable.ic_wifi),
                contentDescription = stringResource(R.string.accessibility_connection_local),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (server.remote != null) {
            Icon(
                painter = painterResource(R.drawable.ic_cloud_connected),
                contentDescription = stringResource(R.string.accessibility_connection_remote),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (server.proxy != null) {
            Icon(
                painter = painterResource(R.drawable.ic_vpn_key),
                contentDescription = stringResource(R.string.accessibility_connection_proxy),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun ServerListItemSavedPreview() {
    SendSpinTheme {
        ServerListItem(
            server = UnifiedServer(
                id = "1",
                name = "Living Room Speaker",
                lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                local = LocalConnection("192.168.1.100:8927"),
                remote = RemoteConnection("ABCDE12345FGHIJ67890KLMNO1"),
                isDefaultServer = true
            ),
            status = ServerItemStatus.ONLINE,
            onClick = {},
            onLongClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerListItemDiscoveredPreview() {
    SendSpinTheme {
        ServerListItem(
            server = UnifiedServer(
                id = "2",
                name = "Kitchen Speaker",
                local = LocalConnection("192.168.1.101:8927"),
                isDiscovered = true
            ),
            status = ServerItemStatus.ONLINE,
            onClick = {},
            onLongClick = {},
            onQuickConnectClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerListItemConnectingPreview() {
    SendSpinTheme {
        ServerListItem(
            server = UnifiedServer(
                id = "3",
                name = "Bedroom Speaker",
                local = LocalConnection("192.168.1.102:8927"),
                proxy = ProxyConnection("https://proxy.example.com", "token123")
            ),
            status = ServerItemStatus.CONNECTING,
            onClick = {},
            onLongClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerListItemReconnectingPreview() {
    SendSpinTheme {
        ServerListItem(
            server = UnifiedServer(
                id = "4",
                name = "Office Speaker",
                local = LocalConnection("192.168.1.103:8927")
            ),
            status = ServerItemStatus.RECONNECTING,
            onClick = {},
            onLongClick = {},
            reconnectAttempt = 2,
            reconnectMaxAttempts = 5
        )
    }
}
