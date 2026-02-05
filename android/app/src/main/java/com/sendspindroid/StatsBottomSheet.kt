package com.sendspindroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sendspindroid.ui.stats.StatsContent
import com.sendspindroid.ui.stats.StatsViewModel
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Stats for Nerds - Bottom Sheet showing real-time audio synchronization diagnostics.
 *
 * Displays detailed stats about:
 * - Clock synchronization (Kalman filter state)
 * - Audio buffering (chunks queued, played, dropped)
 * - Sync correction (sample insert/drop)
 * - Playback state machine
 *
 * Updates at 2 Hz (500ms intervals) for efficient real-time monitoring.
 *
 * ## Usage
 * ```kotlin
 * StatsBottomSheet().show(supportFragmentManager, "stats")
 * ```
 */
class StatsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "StatsBottomSheet"
    }

    private val viewModel: StatsViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val statsState by viewModel.statsState.collectAsState()

                SendSpinTheme {
                    StatsContent(state = statsState)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopPolling()
    }
}
