package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

/**
 * Proxy configuration step - enter proxy URL and authentication.
 */
@Composable
fun ProxyStep(
    proxyUrl: String,
    authMode: ProxyAuthMode,
    username: String,
    password: String,
    token: String,
    onProxyUrlChange: (String) -> Unit,
    onAuthModeChange: (ProxyAuthMode) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // Title and description
        Text(
            text = stringResource(R.string.wizard_proxy_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wizard_proxy_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Proxy URL input
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

        // Auth mode tabs
        TabRow(
            selectedTabIndex = authMode.ordinal
        ) {
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

        // Auth fields based on mode
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
}

@Preview(showBackground = true)
@Composable
private fun ProxyStepLoginPreview() {
    SendSpinTheme {
        ProxyStep(
            proxyUrl = "https://proxy.example.com/music",
            authMode = ProxyAuthMode.LOGIN,
            username = "",
            password = "",
            token = "",
            onProxyUrlChange = {},
            onAuthModeChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTokenChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProxyStepTokenPreview() {
    SendSpinTheme {
        ProxyStep(
            proxyUrl = "https://proxy.example.com/music",
            authMode = ProxyAuthMode.TOKEN,
            username = "",
            password = "",
            token = "",
            onProxyUrlChange = {},
            onAuthModeChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onTokenChange = {}
        )
    }
}
