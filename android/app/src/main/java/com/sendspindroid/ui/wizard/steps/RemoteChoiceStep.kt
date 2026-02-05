package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.RemoteAccessMethod

/**
 * Remote choice step - select how to access the server remotely.
 */
@Composable
fun RemoteChoiceStep(
    selectedMethod: RemoteAccessMethod,
    onMethodSelected: (RemoteAccessMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // Title and description
        Text(
            text = stringResource(R.string.wizard_remote_choice_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wizard_remote_choice_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Options - Order: Local Only -> Remote Access ID -> Reverse Proxy
        Column(modifier = Modifier.selectableGroup()) {
            RemoteChoiceOption(
                icon = R.drawable.ic_wifi,
                title = stringResource(R.string.wizard_local_only_option),
                description = stringResource(R.string.wizard_local_only_option_desc),
                isSelected = selectedMethod == RemoteAccessMethod.NONE,
                onClick = { onMethodSelected(RemoteAccessMethod.NONE) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            RemoteChoiceOption(
                icon = R.drawable.ic_cloud_connected,
                title = stringResource(R.string.wizard_remote_id_option),
                description = stringResource(R.string.wizard_remote_id_option_desc),
                isSelected = selectedMethod == RemoteAccessMethod.REMOTE_ID,
                onClick = { onMethodSelected(RemoteAccessMethod.REMOTE_ID) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            RemoteChoiceOption(
                icon = R.drawable.ic_vpn_key,
                title = stringResource(R.string.wizard_proxy_option),
                description = stringResource(R.string.wizard_proxy_option_desc),
                isSelected = selectedMethod == RemoteAccessMethod.PROXY,
                onClick = { onMethodSelected(RemoteAccessMethod.PROXY) }
            )
        }
    }
}

@Composable
private fun RemoteChoiceOption(
    icon: Int,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null // handled by selectable modifier
            )

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteChoiceStepPreview() {
    SendSpinTheme {
        RemoteChoiceStep(
            selectedMethod = RemoteAccessMethod.REMOTE_ID,
            onMethodSelected = {}
        )
    }
}
