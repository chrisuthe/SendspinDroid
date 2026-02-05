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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.sendspindroid.ui.wizard.ConnectionTestState

/**
 * Music Assistant login step - enter credentials for MA integration.
 */
@Composable
fun MaLoginStep(
    username: String,
    password: String,
    port: Int,
    testState: ConnectionTestState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onTestConnection: () -> Unit,
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
            text = stringResource(R.string.wizard_ma_login_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wizard_ma_login_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Username field
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ma_username)) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_person),
                    contentDescription = null
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ma_password)) },
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

        Spacer(modifier = Modifier.height(16.dp))

        // Port field
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { onPortChange(it) }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ma_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Test connection button
        Button(
            onClick = onTestConnection,
            modifier = Modifier.fillMaxWidth(),
            enabled = testState !is ConnectionTestState.Testing
        ) {
            when (testState) {
                is ConnectionTestState.Testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                else -> {
                    Text(stringResource(R.string.wizard_ma_test_connection))
                }
            }
        }

        // Test result
        when (testState) {
            is ConnectionTestState.Success -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = testState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is ConnectionTestState.Failed -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = testState.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MaLoginStepPreview() {
    SendSpinTheme {
        MaLoginStep(
            username = "",
            password = "",
            port = 8095,
            testState = ConnectionTestState.Idle,
            onUsernameChange = {},
            onPasswordChange = {},
            onPortChange = {},
            onTestConnection = {}
        )
    }
}
