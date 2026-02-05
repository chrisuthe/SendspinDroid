package com.sendspindroid.ui.main

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.main.components.MiniPlayer
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * AbstractComposeView that hosts the MiniPlayer composable.
 * This replaces the XML mini player layout with a Compose implementation.
 */
class MiniPlayerComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    var viewModel: MainActivityViewModel? = null
        set(value) {
            field = value
            // Trigger recomposition when ViewModel is set
            disposeComposition()
        }

    var onCardClick: (() -> Unit)? = null
    var onStopClick: (() -> Unit)? = null
    var onPlayPauseClick: (() -> Unit)? = null
    var onVolumeChange: ((Float) -> Unit)? = null

    @Composable
    override fun Content() {
        val vm = viewModel ?: return

        val metadata by vm.metadata.collectAsState()
        val artworkSource by vm.artworkSource.collectAsState()
        val isPlaying by vm.isPlaying.collectAsState()
        val volume by vm.volume.collectAsState()
        val connectionState by vm.connectionState.collectAsState()

        // Get server name from connection state
        val serverName = when (val state = connectionState) {
            is AppConnectionState.Connected -> state.serverName
            is AppConnectionState.Reconnecting -> state.serverName
            else -> ""
        }

        SendSpinTheme {
            MiniPlayer(
                serverName = serverName,
                metadata = metadata,
                artworkSource = artworkSource,
                isPlaying = isPlaying,
                volume = volume,
                onCardClick = { onCardClick?.invoke() },
                onStopClick = { onStopClick?.invoke() },
                onPlayPauseClick = { onPlayPauseClick?.invoke() },
                onVolumeChange = { newVolume ->
                    onVolumeChange?.invoke(newVolume) ?: vm.updateVolume(newVolume)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
