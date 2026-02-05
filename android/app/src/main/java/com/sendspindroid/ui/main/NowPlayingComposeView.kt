package com.sendspindroid.ui.main

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * A custom ComposeView that hosts the NowPlayingScreen.
 *
 * This allows embedding the Compose-based NowPlaying UI within the existing
 * XML-based MainActivity layout during the incremental migration.
 *
 * Usage in XML:
 * ```xml
 * <com.sendspindroid.ui.main.NowPlayingComposeView
 *     android:id="@+id/nowPlayingComposeView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * Then in Activity:
 * ```kotlin
 * binding.nowPlayingComposeView.apply {
 *     setViewModel(viewModel)
 *     setCallbacks(...)
 * }
 * ```
 */
class NowPlayingComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var viewModel: MainActivityViewModel? = null

    // Callback handlers
    private var onPreviousClick: () -> Unit = {}
    private var onPlayPauseClick: () -> Unit = {}
    private var onNextClick: () -> Unit = {}
    private var onSwitchGroupClick: () -> Unit = {}
    private var onFavoriteClick: () -> Unit = {}
    private var onVolumeChange: (Float) -> Unit = {}

    /**
     * Sets the ViewModel that provides state for this view.
     */
    fun setViewModel(viewModel: MainActivityViewModel) {
        this.viewModel = viewModel
    }

    /**
     * Sets all callback handlers at once.
     */
    fun setCallbacks(
        onPreviousClick: () -> Unit,
        onPlayPauseClick: () -> Unit,
        onNextClick: () -> Unit,
        onSwitchGroupClick: () -> Unit,
        onFavoriteClick: () -> Unit,
        onVolumeChange: (Float) -> Unit
    ) {
        this.onPreviousClick = onPreviousClick
        this.onPlayPauseClick = onPlayPauseClick
        this.onNextClick = onNextClick
        this.onSwitchGroupClick = onSwitchGroupClick
        this.onFavoriteClick = onFavoriteClick
        this.onVolumeChange = onVolumeChange
    }

    @Composable
    override fun Content() {
        val vm = viewModel
        if (vm != null) {
            SendSpinTheme {
                NowPlayingScreen(
                    viewModel = vm,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onFavoriteClick = onFavoriteClick,
                    onVolumeChange = onVolumeChange
                )
            }
        }
    }
}
