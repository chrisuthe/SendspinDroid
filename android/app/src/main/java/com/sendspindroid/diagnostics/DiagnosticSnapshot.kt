package com.sendspindroid.diagnostics

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.sendspindroid.playback.PlaybackService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Captures the live diagnostic stats from [PlaybackService] as a text block for
 * bug reports. The same data shown in "Stats for Nerds", obtained one-shot via a
 * short-lived [MediaController] so any Context (e.g. Settings) can request it.
 *
 * Returned text is **not** redacted here -- the report path redacts the whole
 * bundle (snapshot + logs) uniformly. See [DiagnosticsExport].
 */
object DiagnosticSnapshot {

    private const val TIMEOUT_MS = 3_000L

    suspend fun capture(context: Context): String {
        val bundle = withTimeoutOrNull(TIMEOUT_MS) { requestStats(context.applicationContext) }
        return if (bundle == null || bundle.isEmpty) {
            "--- Diagnostic snapshot ---\n(unavailable -- not connected to a server)"
        } else {
            format(bundle)
        }
    }

    private suspend fun requestStats(context: Context): Bundle? =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                val controller = runCatching { future.get() }.getOrNull()
                if (controller == null) {
                    if (cont.isActive) cont.resume(null)
                    return@addListener
                }
                val command = SessionCommand(PlaybackService.COMMAND_GET_STATS, Bundle.EMPTY)
                val result = controller.sendCustomCommand(command, Bundle.EMPTY)
                result.addListener({
                    val extras = runCatching { result.get().extras }.getOrNull()
                    controller.release()
                    if (cont.isActive) cont.resume(extras)
                }, context.mainExecutor)
            }, context.mainExecutor)
        }

    private fun format(bundle: Bundle): String = buildString {
        appendLine("--- Diagnostic snapshot ---")
        for (key in bundle.keySet().sorted()) {
            @Suppress("DEPRECATION")
            appendLine("$key=${bundle.get(key)}")
        }
    }
}
