package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.ProxyAuthMode
import com.sendspindroid.ui.wizard.RemoteAccessMethod

/**
 * Tabbed remote setup step — combines Remote ID and Reverse Proxy
 * into a single screen with tabs.
 *
 * Used for both MA_RemoteSetup (local+remote) and MA_RemoteOnlySetup (remote-only).
 */
@Composable
fun RemoteSetupStep(
    remoteAccessMethod: RemoteAccessMethod,
    remoteId: String,
    proxyUrl: String,
    proxyAuthMode: ProxyAuthMode,
    proxyUsername: String,
    proxyPassword: String,
    proxyToken: String,
    onMethodChange: (RemoteAccessMethod) -> Unit,
    onRemoteIdChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onProxyUrlChange: (String) -> Unit,
    onAuthModeChange: (ProxyAuthMode) -> Unit,
    onProxyUsernameChange: (String) -> Unit,
    onProxyPasswordChange: (String) -> Unit,
    onProxyTokenChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTab = if (remoteAccessMethod == RemoteAccessMethod.PROXY) 1 else 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Connect Remotely",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Enter your Music Assistant Remote ID or reverse proxy details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Method tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onMethodChange(RemoteAccessMethod.REMOTE_ID) },
                text = { Text("Remote ID") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onMethodChange(RemoteAccessMethod.PROXY) },
                text = { Text("Reverse Proxy") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tab content (scrollable)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (selectedTab) {
                0 -> RemoteIdTabContent(
                    remoteId = remoteId,
                    onRemoteIdChange = onRemoteIdChange,
                    onScanQr = onScanQr
                )
                1 -> ProxyTabContent(
                    proxyUrl = proxyUrl,
                    authMode = proxyAuthMode,
                    username = proxyUsername,
                    password = proxyPassword,
                    token = proxyToken,
                    onProxyUrlChange = onProxyUrlChange,
                    onAuthModeChange = onAuthModeChange,
                    onUsernameChange = onProxyUsernameChange,
                    onPasswordChange = onProxyPasswordChange,
                    onTokenChange = onProxyTokenChange
                )
            }
        }
    }
}

@Composable
private fun RemoteIdTabContent(
    remoteId: String,
    onRemoteIdChange: (String) -> Unit,
    onScanQr: () -> Unit
) {
    OutlinedTextField(
        value = remoteId,
        onValueChange = onRemoteIdChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.remote_id)) },
        placeholder = { Text(stringResource(R.string.remote_id_hint)) },
        singleLine = true,
        trailingIcon = {
            FilledTonalButton(
                onClick = onScanQr,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = stringResource(R.string.scan_qr),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )

    Spacer(modifier = Modifier.height(24.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.wizard_remote_id_info_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.wizard_remote_id_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProxyTabContent(
    proxyUrl: String,
    authMode: ProxyAuthMode,
    username: String,
    password: String,
    token: String,
    onProxyUrlChange: (String) -> Unit,
    onAuthModeChange: (ProxyAuthMode) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit
) {
    OutlinedTextField(
        value = proxyUrl,
        onValueChange = onProxyUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.proxy_url)) },
        placeholder = { Text(stringResource(R.string.proxy_url_hint)) },
        singleLine = true,
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_vpn_key),
                contentDescription = null
            )
        }
    )

    Spacer(modifier = Modifier.height(24.dp))

    TabRow(selectedTabIndex = authMode.ordinal) {
        Tab(
            selected = authMode == ProxyAuthMode.LOGIN,
            onClick = { onAuthModeChange(ProxyAuthMode.LOGIN) },
            text = { Text(stringResource(R.string.proxy_auth_login)) }
        )
        Tab(
            selected = authMode == ProxyAuthMode.TOKEN,
            onClick = { onAuthModeChange(ProxyAuthMode.TOKEN) },
            text = { Text(stringResource(R.string.proxy_auth_token)) }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    when (authMode) {
        ProxyAuthMode.LOGIN -> {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_username)) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_person),
                        contentDescription = null
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null
                    )
                }
            )
        }

        ProxyAuthMode.TOKEN -> {
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.proxy_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteSetupStepRemoteIdPreview() {
    SendSpinTheme {
        RemoteSetupStep(
            remoteAccessMethod = RemoteAccessMethod.REMOTE_ID,
            remoteId = "",
            proxyUrl = "",
            proxyAuthMode = ProxyAuthMode.LOGIN,
            proxyUsername = "",
            proxyPassword = "",
            proxyToken = "",
            onMethodChange = {},
            onRemoteIdChange = {},
            onScanQr = {},
            onProxyUrlChange = {},
            onAuthModeChange = {},
            onProxyUsernameChange = {},
            onProxyPasswordChange = {},
            onProxyTokenChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteSetupStepProxyPreview() {
    SendSpinTheme {
        RemoteSetupStep(
            remoteAccessMethod = RemoteAccessMethod.PROXY,
            remoteId = "",
            proxyUrl = "https://proxy.example.com",
            proxyAuthMode = ProxyAuthMode.LOGIN,
            proxyUsername = "",
            proxyPassword = "",
            proxyToken = "",
            onMethodChange = {},
            onRemoteIdChange = {},
            onScanQr = {},
            onProxyUrlChange = {},
            onAuthModeChange = {},
            onProxyUsernameChange = {},
            onProxyPasswordChange = {},
            onProxyTokenChange = {}
        )
    }
}
