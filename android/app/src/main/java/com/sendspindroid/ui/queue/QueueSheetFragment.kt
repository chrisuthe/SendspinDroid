package com.sendspindroid.ui.queue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * BottomSheetDialogFragment that hosts the Compose-based QueueBottomSheet.
 *
 * This bridges the XML-based MainActivity with the Compose queue UI.
 * It's shown as a modal bottom sheet over the Now Playing view.
 */
class QueueSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: QueueViewModel by viewModels()

    /**
     * Callback for navigating to the library tab.
     * Set by the Activity before showing the fragment.
     */
    var onBrowseLibrary: (() -> Unit)? = null

    override fun getTheme(): Int = R.style.Theme_SendSpinDroid_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SendSpinTheme {
                    // Surface sets LocalContentColor so all child Text composables
                    // inherit the correct theme-aware text color
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        QueueSheetContent(
                            viewModel = viewModel,
                            onBrowseLibrary = {
                                onBrowseLibrary?.invoke()
                                dismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "QueueSheetFragment"

        fun newInstance(): QueueSheetFragment {
            return QueueSheetFragment()
        }
    }
}
