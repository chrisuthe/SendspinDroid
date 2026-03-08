package com.sendspindroid.ui.main.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Dot color indicating connection health.
 */
private val ConnectedGreen = Color(0xFF4CAF50)
private val ReconnectingAmber = Color(0xFFFFA726)
private val ConnectingAmber = Color(0xFFFFA726)
private val ErrorRed = Color(0xFFEF5350)

/**
 * Small status dot that reflects the current connection state.
 *
 * - Connected: solid green dot
 * - Connecting: pulsing amber dot
 * - Reconnecting: pulsing amber dot
 * - Error: solid red dot
 */
@Composable
fun ConnectionStatusDot(
    connectionState: AppConnectionState,
    modifier: Modifier = Modifier
) {
    val (color, shouldPulse, description) = when (connectionState) {
        is AppConnectionState.Connected -> Triple(ConnectedGreen, false, "Connected")
        is AppConnectionState.Connecting -> Triple(ConnectingAmber, true, "Connecting")
        is AppConnectionState.Reconnecting -> Triple(ReconnectingAmber, true, "Reconnecting")
        is AppConnectionState.Error -> Triple(ErrorRed, false, "Connection error")
        is AppConnectionState.ServerList -> Triple(Color.Transparent, false, "")
    }

    if (connectionState is AppConnectionState.ServerList) return

    val alpha = if (shouldPulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        animatedAlpha
    } else {
        1f
    }

    Canvas(
        modifier = modifier
            .size(8.dp)
            .semantics { contentDescription = description }
    ) {
        drawCircle(
            color = color,
            alpha = alpha
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusDotConnectedPreview() {
    SendSpinTheme {
        ConnectionStatusDot(
            connectionState = AppConnectionState.Connected("Living Room", "192.168.1.100")
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusDotReconnectingPreview() {
    SendSpinTheme {
        ConnectionStatusDot(
            connectionState = AppConnectionState.Reconnecting(
                "Living Room", "192.168.1.100", attempt = 2, nextRetrySeconds = 5
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusDotErrorPreview() {
    SendSpinTheme {
        ConnectionStatusDot(
            connectionState = AppConnectionState.Error("Connection refused")
        )
    }
}
