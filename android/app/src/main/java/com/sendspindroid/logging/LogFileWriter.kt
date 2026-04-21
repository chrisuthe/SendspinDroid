package com.sendspindroid.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File I/O owner for on-device logs.
 *
 * Writes to [dir], rotates up to [maxFiles] files of [maxBytesPerFile] each. The active (newest)
 * file is always `sendspin-log-0.txt`; older files are `sendspin-log-1.txt` ... `sendspin-log-N.txt`
 * with higher indices being older.
 *
 * All file operations are guarded by a single lock; writer assumes a single producer coroutine
 * ([LogcatBridge]) in practice, but `clear()` and `shareIntent()` may run on any thread.
 */
internal class LogFileWriter(
    private val dir: File,
    private val maxFiles: Int = 10,
    private val maxBytesPerFile: Long = 1 * 1024 * 1024,
) {
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init() {
        synchronized(lock) {
            dir.mkdirs()
            val active = activeFile()
            if (!active.exists() || active.length() == 0L) {
                active.writeText(header("Initialized"))
            }
        }
    }

    fun appendLine(line: String) {
        synchronized(lock) {
            try {
                if (activeFile().length() >= maxBytesPerFile) {
                    rotate()
                }
                activeFile().appendText("$line\n")
                if (activeFile().length() >= maxBytesPerFile) {
                    rotate()
                }
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    fun appendRaw(block: String) {
        synchronized(lock) {
            try {
                if (activeFile().length() >= maxBytesPerFile) {
                    rotate()
                }
                activeFile().appendText(block)
                if (activeFile().length() >= maxBytesPerFile) {
                    rotate()
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            for (i in 0 until maxFiles) {
                val f = File(dir, "sendspin-log-$i.txt")
                if (f.exists()) f.delete()
            }
            activeFile().writeText(header("Cleared"))
        }
    }

    /** Returns existing files in oldest -> newest order (highest index first). */
    fun currentFiles(): List<File> {
        synchronized(lock) {
            return (maxFiles - 1 downTo 0)
                .map { File(dir, "sendspin-log-$it.txt") }
                .filter { it.exists() }
        }
    }

    fun shareIntent(context: Context): Intent? {
        // Implemented in Task 3. For now return null so tests in this task don't depend on it.
        return null
    }

    private fun activeFile(): File = File(dir, "sendspin-log-0.txt")

    private fun rotate() {
        // Drop the oldest file.
        val oldest = File(dir, "sendspin-log-${maxFiles - 1}.txt")
        if (oldest.exists()) oldest.delete()

        // Rename log-(N-2) -> log-(N-1), ..., log-0 -> log-1.
        for (i in (maxFiles - 2) downTo 0) {
            val src = File(dir, "sendspin-log-$i.txt")
            val dst = File(dir, "sendspin-log-${i + 1}.txt")
            if (src.exists()) src.renameTo(dst)
        }

        // Fresh log-0 with rotation header.
        activeFile().writeText(header("Rotated"))
    }

    private fun header(reason: String): String {
        val ts = dateFormat.format(Date())
        return "=== $reason at $ts ===\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "${"=".repeat(50)}\n"
    }

    // Unused in Task 2; wired in Task 3.
    @Suppress("unused")
    private fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

    // Unused in Task 2; kept private to suppress lints.
    @Suppress("unused")
    private fun unusedFileProvider(ctx: Context, file: File) = FileProvider.getUriForFile(ctx, fileProviderAuthority(ctx), file)
}
