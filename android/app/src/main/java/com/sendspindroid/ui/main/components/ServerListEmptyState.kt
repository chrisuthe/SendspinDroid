package com.sendspindroid.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Welcome / empty state shown when no saved servers are available.
 *
 * Displays a hero card with a prominent "Add Your First Server" CTA,
 * a scanning status indicator, and optionally discovered servers below
 * for quick-connect.
 */
@Composable
fun ServerListEmptyState(
    isScanning: Boolean,
    discoveredServers: List<UnifiedServer>,
    onAddServerClick: () -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // Hero card
        item(key = "hero") {
            HeroCard(
                isScanning = isScanning,
                discoveredCount = discoveredServers.size,
                onAddServerClick = onAddServerClick
            )
        }

        // Discovered servers section — appears when servers are found
        if (discoveredServers.isNotEmpty()) {
            item(key = "divider") {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "quick_connect_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.welcome_quick_connect),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.welcome_found_on_network),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            itemsIndexed(
                items = discoveredServers,
                key = { _, server -> "quick_${server.id}" }
            ) { index, server ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = 300,
                            delayMillis = index * 80
                        )
                    ) + slideInVertically(
                        initialOffsetY = { 60 },
                        animationSpec = tween(
                            durationMillis = 300,
                            delayMillis = index * 80
                        )
                    )
                ) {
                    ServerListItem(
                        server = server,
                        status = ServerItemStatus.ONLINE,
                        onClick = { onQuickConnectClick(server) },
                        onLongClick = { /* no-op for quick connect */ },
                        onQuickConnectClick = { onQuickConnectClick(server) }
                    )
                }
            }
        }
    }
}

/**
 * Hero card with welcome message and primary CTA.
 */
@Composable
private fun HeroCard(
    isScanning: Boolean,
    discoveredCount: Int,
    onAddServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero icon with background circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_speaker_group),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Welcome title
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Primary CTA — Add Server
            FilledTonalButton(
                onClick = onAddServerClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.welcome_add_first_server),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // "or" divider
            Text(
                text = stringResource(R.string.welcome_or),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Scanning status chip
            DiscoveryStatusChip(
                isScanning = isScanning,
                discoveredCount = discoveredCount
            )
        }
    }
}

/**
 * Status chip showing current mDNS discovery state.
 *
 * - Scanning: pulsing wifi icon + "Searching for servers nearby..."
 * - Found:   static wifi icon + "N server(s) found nearby"
 * - None:    wifi-off style   + "No servers found on this network"
 */
@Composable
private fun DiscoveryStatusChip(
    isScanning: Boolean,
    discoveredCount: Int,
    modifier: Modifier = Modifier
) {
    // Pulsing alpha for scanning state
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_pulse_alpha"
    )

    when {
        isScanning && discoveredCount == 0 -> {
            // Still scanning, nothing found yet
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_scanning),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .alpha(pulseAlpha)
                    )
                },
                modifier = modifier,
                enabled = false
            )
        }
        discoveredCount > 0 -> {
            // Servers found
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_servers_found, discoveredCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = modifier,
                enabled = false
            )
        }
        else -> {
            // Done scanning, nothing found
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_no_servers_found),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .alpha(0.4f)
                    )
                },
                modifier = modifier,
                enabled = false
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateScanningPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = true,
            discoveredServers = emptyList(),
            onAddServerClick = {},
            onQuickConnectClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateWithServersPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = false,
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
            onAddServerClick = {},
            onQuickConnectClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateNoServersFoundPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = false,
            discoveredServers = emptyList(),
            onAddServerClick = {},
            onQuickConnectClick = {}
        )
    }
}
