# On-Device Logging Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `FileLogger`/`DebugLogger` pair with a categorized `AppLog` facade plus a `logcat`-subprocess bridge that captures all `android.util.Log.x` call sites into a rotated on-device file (up to 10 x 1 MB). Replace the Settings debug toggle with a 6-level segmented control.

**Architecture:** Hybrid. `AppLog` exposes per-category sub-loggers (`AppLog.Audio.d(...)`) that delegate to `android.util.Log`. A `LogcatBridge` coroutine reads `logcat --pid=<self>` output and writes every line to `LogFileWriter` (the single file-I/O owner). Level is a global `LogLevel` gated in the facade and also passed as the bridge's logcat-priority filter.

**Tech Stack:** Kotlin, Android SDK (android.util.Log, java.lang.ProcessBuilder, FileProvider), Jetpack Compose (Material 3 SegmentedButtonRow), JUnit 4, MockK, Robolectric, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-04-20-logging-design.md`

---

## File Structure

**New files (`android/app/src/main/java/com/sendspindroid/logging/`):**

| File                | Responsibility                                               |
|---------------------|--------------------------------------------------------------|
| `LogLevel.kt`       | Enum `VERBOSE/DEBUG/INFO/WARN/ERROR/OFF` + priority helpers  |
| `LogCategory.kt`    | Enum with 9 categories + tag string, package-mapping KDoc    |
| `LogFileWriter.kt`  | Rotation, concatenation, share-intent, clear                 |
| `LogcatBridge.kt`   | Subprocess reader, lifecycle (start/stop/setLevel)           |
| `AppLog.kt`         | Public facade + `Logger` class + `session` object + `init`   |

**New test files:**

| File                                                                                                | Type           |
|-----------------------------------------------------------------------------------------------------|----------------|
| `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterTest.kt`                          | Unit           |
| `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterShareIntentTest.kt`               | Robolectric    |
| `android/app/src/test/java/com/sendspindroid/logging/AppLogTest.kt`                                 | Robolectric    |
| `android/app/src/androidTest/java/com/sendspindroid/logging/LogcatBridgeInstrumentedTest.kt`        | Instrumented   |

**Deleted files (in final migration task):**

- `android/app/src/main/java/com/sendspindroid/debug/FileLogger.kt`
- `android/app/src/main/java/com/sendspindroid/debug/DebugLogger.kt`
- `android/app/src/test/java/com/sendspindroid/debug/FileLoggerConcurrencyTest.kt`
- `android/app/src/test/java/com/sendspindroid/debug/DebugLoggerSessionFieldsTest.kt`

**Modified files:**

- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (init wiring)
- `android/app/src/main/java/com/sendspindroid/SettingsActivity.kt` (share intent call)
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` (session markers, broadcast, stats)
- `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt` (full call-site migration)
- `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsViewModel.kt` (level state, migration)
- `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsScreen.kt` (SegmentedButtonRow + Clear Logs)
- `android/app/src/main/res/values/strings.xml` (add/remove strings)

**Unchanged but verified:**

- `android/app/src/main/res/xml/file_paths.xml` -- already exposes `cache/` with `path="."`, which covers the new `cache/logs/` subdirectory. No change needed.
- `android/app/src/main/AndroidManifest.xml` -- FileProvider already declared.

---

## Build verification

Between tasks, build with:

```bash
cd android && ./gradlew assembleDebug
```

Run unit tests with:

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Run instrumented tests (requires emulator/device):

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest
```

---

## Task 1: Add `LogLevel` and `LogCategory` enums

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/logging/LogLevel.kt`
- Create: `android/app/src/main/java/com/sendspindroid/logging/LogCategory.kt`

No tests -- enums carry no behavior beyond compile-time constants.

- [ ] **Step 1: Create `LogLevel.kt`**

```kotlin
package com.sendspindroid.logging

/**
 * Global log level for the app.
 *
 * Ordered from most verbose to silent. Each level permits itself and all levels above it in severity:
 * VERBOSE permits every call, OFF permits none.
 *
 * Used by [AppLog] to gate facade calls and by [LogcatBridge] to filter the logcat subprocess output.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    /**
     * Logcat priority letter for the `*:<priority>` filter argument.
     * OFF is not valid for the subprocess -- the bridge should be stopped instead.
     */
    fun logcatPriorityChar(): Char = when (this) {
        VERBOSE -> 'V'
        DEBUG -> 'D'
        INFO -> 'I'
        WARN -> 'W'
        ERROR -> 'E'
        OFF -> throw IllegalStateException("OFF has no logcat priority; stop the bridge instead")
    }

    /**
     * True if a log call at [callLevel] should be emitted given the current gate level.
     * OFF permits nothing.
     */
    fun permits(callLevel: LogLevel): Boolean {
        if (this == OFF) return false
        return callLevel.ordinal >= this.ordinal
    }
}
```

- [ ] **Step 2: Create `LogCategory.kt`**

```kotlin
package com.sendspindroid.logging

/**
 * Categories for log facade routing.
 *
 * Every tag value starts with `SendSpin.` so facade output is trivially greppable in logcat and in
 * the shared log file. [LogcatBridge] filters by priority (not by tag), so raw `android.util.Log.x`
 * calls from elsewhere in the app are captured regardless of their tag string.
 *
 * ## Package-to-category mapping
 *
 * When migrating raw `Log.x` calls opportunistically, use this table:
 *
 * | Package                                | Category         |
 * |----------------------------------------|------------------|
 * | `sendspin/` (audio, decoders)          | `Audio`          |
 * | `sendspin/` (clock sync code)          | `Sync`           |
 * | `sendspin/protocol/`                   | `Protocol`       |
 * | `network/`, `discovery/`               | `Network`        |
 * | `playback/`                            | `Playback`       |
 * | `musicassistant/`                      | `MusicAssistant` |
 * | `remote/`                              | `Remote`         |
 * | `ui/`                                  | `UI`             |
 * | root, settings, boot receiver, etc.    | `App`            |
 */
enum class LogCategory(val tag: String) {
    Audio("SendSpin.Audio"),
    Sync("SendSpin.Sync"),
    Protocol("SendSpin.Protocol"),
    Network("SendSpin.Network"),
    Playback("SendSpin.Playback"),
    MusicAssistant("SendSpin.MA"),
    Remote("SendSpin.Remote"),
    UI("SendSpin.UI"),
    App("SendSpin.App");
}
```

- [ ] **Step 3: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/LogLevel.kt app/src/main/java/com/sendspindroid/logging/LogCategory.kt
git commit -m "feat(logging): add LogLevel and LogCategory enums"
```

---

## Task 2: `LogFileWriter` -- rotation, clear, currentFiles (TDD)

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt`
- Create: `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterTest.kt`

**Behavior under test:** rotation at size threshold, rotation cap at `maxFiles`, line atomicity at the rotation boundary, `clear()` removal + re-init, `currentFiles()` ordering.

Tests deliberately do NOT exercise `shareIntent` (that needs `Context` for `FileProvider` and lives in the next task).

- [ ] **Step 1: Write the failing test file**

File: `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterTest.kt`

```kotlin
package com.sendspindroid.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogFileWriterTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "logwriter-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 1024L)
        writer.init()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `init creates log-0 with header`() {
        val log0 = File(tempDir, "sendspin-log-0.txt")
        assertTrue("log-0 should exist after init", log0.exists())
        assertTrue("log-0 should contain a header line", log0.readText().contains("==="))
    }

    @Test
    fun `appendLine writes to log-0 below threshold`() {
        writer.appendLine("hello world")
        val content = File(tempDir, "sendspin-log-0.txt").readText()
        assertTrue("log-0 should contain appended line", content.contains("hello world"))
    }

    @Test
    fun `rotation triggers when file exceeds maxBytes`() {
        val line = "x".repeat(200)
        repeat(10) { writer.appendLine(line) }

        val log0 = File(tempDir, "sendspin-log-0.txt")
        val log1 = File(tempDir, "sendspin-log-1.txt")

        assertTrue("log-1 should exist after rotation", log1.exists())
        assertTrue("log-0 should be under threshold after rotation", log0.length() < 1024L)
    }

    @Test
    fun `rotation respects maxFiles cap`() {
        val line = "x".repeat(500)
        repeat(30) { writer.appendLine(line) }

        val allFiles = (0..5).map { File(tempDir, "sendspin-log-$it.txt") }
        val existing = allFiles.count { it.exists() }
        assertEquals("exactly maxFiles should exist", 3, existing)
        assertFalse("log-3 must not exist beyond cap", File(tempDir, "sendspin-log-3.txt").exists())
    }

    @Test
    fun `rotation preserves line atomicity`() {
        val near = "y".repeat(900)
        writer.appendLine(near)
        val bigLine = "z".repeat(400)
        writer.appendLine(bigLine)

        val log0 = File(tempDir, "sendspin-log-0.txt").readText()
        val log1 = File(tempDir, "sendspin-log-1.txt").readText()

        val log0HasBig = log0.contains(bigLine)
        val log1HasBig = log1.contains(bigLine)
        assertTrue("big line should appear in exactly one file",
            log0HasBig.xor(log1HasBig))
    }

    @Test
    fun `clear removes all rotated files and resets log-0`() {
        val line = "x".repeat(500)
        repeat(10) { writer.appendLine(line) }
        assertTrue("log-1 should exist before clear", File(tempDir, "sendspin-log-1.txt").exists())

        writer.clear()

        assertFalse("log-1 should be removed", File(tempDir, "sendspin-log-1.txt").exists())
        val log0 = File(tempDir, "sendspin-log-0.txt")
        assertTrue("log-0 should be recreated", log0.exists())
        assertTrue("log-0 should contain a fresh header", log0.readText().contains("==="))
    }

    @Test
    fun `currentFiles returns oldest to newest`() {
        val line = "x".repeat(500)
        repeat(10) { writer.appendLine(line) }

        val files = writer.currentFiles()
        assertTrue("should have at least two files", files.size >= 2)
        val names = files.map { it.name }
        val expectedOrder = names.sortedByDescending { it.substringAfter("sendspin-log-").substringBefore(".txt").toInt() }
        assertEquals("files should be oldest->newest (highest index first)", expectedOrder, names)
    }
}

private fun Boolean.xor(other: Boolean): Boolean = this != other
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.LogFileWriterTest"`
Expected: FAIL. Compilation error: `LogFileWriter` unresolved reference. This is the expected TDD "red" state.

- [ ] **Step 3: Write the implementation**

File: `android/app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt`

```kotlin
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
                val active = activeFile()
                if (active.length() >= maxBytesPerFile) {
                    rotate()
                }
                activeFile().appendText("$line\n")
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    fun appendRaw(block: String) {
        synchronized(lock) {
            try {
                val active = activeFile()
                if (active.length() >= maxBytesPerFile) {
                    rotate()
                }
                activeFile().appendText(block)
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.LogFileWriterTest"`
Expected: BUILD SUCCESSFUL, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt app/src/test/java/com/sendspindroid/logging/LogFileWriterTest.kt
git commit -m "feat(logging): add LogFileWriter with rotation"
```

---

## Task 3: `LogFileWriter.shareIntent` -- concatenation (TDD via Robolectric)

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt` (real `shareIntent` body)
- Create: `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterShareIntentTest.kt`

Uses Robolectric because `FileProvider.getUriForFile` requires a real `Context` + manifest-registered provider.

- [ ] **Step 1: Write the failing test**

File: `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterShareIntentTest.kt`

```kotlin
package com.sendspindroid.logging

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogFileWriterShareIntentTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        tempDir = File(ctx.cacheDir, "logwriter-share-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 1024L)
        writer.init()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `shareIntent returns null when no logs exist and dir is wiped`() {
        tempDir.deleteRecursively()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = writer.shareIntent(ctx)
        assertEquals("should be null when no files", null, intent)
    }

    @Test
    fun `shareIntent returns SEND intent with a single combined URI`() {
        writer.appendLine("line-A")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = writer.shareIntent(ctx)

        assertNotNull("intent should be non-null when logs exist", intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("text/plain", intent.type)

        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("intent should carry a URI", uri)
    }

    @Test
    fun `shareIntent concatenates all files in oldest-to-newest order`() {
        // Force two files by exceeding maxBytesPerFile.
        val chunk = "a".repeat(500)
        writer.appendLine(chunk) // log-0
        writer.appendLine(chunk) // log-0 ~= 1kB, next append triggers rotate
        writer.appendLine("MARKER_NEW") // in new log-0
        // log-1 now contains the older chunks; log-0 contains MARKER_NEW.

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        writer.shareIntent(ctx)

        // Combined file is the concatenation target.
        val combined = File(tempDir, "sendspin-log-combined.txt")
        assertTrue("combined file should exist", combined.exists())
        val text = combined.readText()

        val idxOld = text.indexOf(chunk)
        val idxNew = text.indexOf("MARKER_NEW")
        assertTrue("both markers should be present", idxOld >= 0 && idxNew >= 0)
        assertTrue("older content should appear before newer in combined file", idxOld < idxNew)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.LogFileWriterShareIntentTest"`
Expected: FAIL. The stub `shareIntent` returns null in all three tests, so the "SEND intent" and "concatenates" tests fail with null assertions.

- [ ] **Step 3: Replace the stub `shareIntent` with the real implementation**

In `LogFileWriter.kt`, replace the existing `shareIntent` method body (and remove the unused helpers `fileProviderAuthority`/`unusedFileProvider`):

```kotlin
fun shareIntent(context: Context): Intent? {
    synchronized(lock) {
        val files = currentFiles()
        if (files.isEmpty()) return null

        val combined = File(dir, "sendspin-log-combined.txt")
        return try {
            combined.writeText(buildShareHeader(context))
            for (f in files) {
                if (f.exists()) {
                    combined.appendText("\n----- ${f.name} -----\n")
                    combined.appendBytes(f.readBytes())
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                combined
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SendSpin Debug Log - ${dateFormat.format(Date())}")
                putExtra(Intent.EXTRA_TEXT, buildShareHeader(context))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }
}

private fun buildShareHeader(context: Context): String {
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
    return buildString {
        appendLine("SendSpin Debug Log")
        appendLine()
        appendLine("App: $versionName")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Generated: ${dateFormat.format(Date())}")
    }
}
```

Also delete the two `@Suppress("unused")` helpers at the bottom of the class -- they were placeholders for Task 2.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.LogFileWriterShareIntentTest"`
Expected: BUILD SUCCESSFUL, all 3 tests pass.

- [ ] **Step 5: Verify nothing broke in Task 2 tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.LogFileWriterTest"`
Expected: BUILD SUCCESSFUL, all 7 tests still pass.

- [ ] **Step 6: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt app/src/test/java/com/sendspindroid/logging/LogFileWriterShareIntentTest.kt
git commit -m "feat(logging): add concatenated shareIntent to LogFileWriter"
```

---

## Task 4: `AppLog` facade, `Logger`, `session`, preference migration (TDD)

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/logging/AppLog.kt`
- Create: `android/app/src/test/java/com/sendspindroid/logging/AppLogTest.kt`

Tests use Robolectric's `ShadowLog` to capture `android.util.Log` calls. The bridge is NOT wired in this task -- `AppLog.init(ctx)` only creates the writer + runs pref migration. Bridge wiring is Task 6.

- [ ] **Step 1: Write the failing tests**

File: `android/app/src/test/java/com/sendspindroid/logging/AppLogTest.kt`

```kotlin
package com.sendspindroid.logging

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class AppLogTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
        AppLog.setLevel(LogLevel.OFF)
    }

    @After
    fun tearDown() {
        AppLog.setLevel(LogLevel.OFF)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
    }

    @Test
    fun `tag prefix contract - every category starts with SendSpin dot`() {
        for (cat in LogCategory.values()) {
            assertTrue("tag for $cat must start with SendSpin.", cat.tag.startsWith("SendSpin."))
        }
    }

    @Test
    fun `level gating - OFF emits nothing`() {
        AppLog.setLevel(LogLevel.OFF)
        AppLog.Audio.v("v")
        AppLog.Audio.d("d")
        AppLog.Audio.i("i")
        AppLog.Audio.w("w")
        AppLog.Audio.e("e")
        val logs = ShadowLog.getLogs().filter { it.tag.startsWith("SendSpin.") }
        assertTrue("no SendSpin logs should be emitted at OFF, found: $logs", logs.isEmpty())
    }

    @Test
    fun `level gating - WARN permits WARN and ERROR only`() {
        AppLog.setLevel(LogLevel.WARN)
        AppLog.Audio.d("must-not-appear")
        AppLog.Audio.i("must-not-appear")
        AppLog.Audio.w("appears-warn")
        AppLog.Audio.e("appears-error")

        val msgs = ShadowLog.getLogs().filter { it.tag == "SendSpin.Audio" }.map { it.msg }
        assertFalse("debug should be gated", msgs.contains("must-not-appear"))
        assertTrue("warn should pass", msgs.contains("appears-warn"))
        assertTrue("error should pass", msgs.contains("appears-error"))
    }

    @Test
    fun `level gating - VERBOSE permits everything`() {
        AppLog.setLevel(LogLevel.VERBOSE)
        AppLog.Protocol.v("v")
        AppLog.Protocol.d("d")
        AppLog.Protocol.i("i")
        AppLog.Protocol.w("w")
        AppLog.Protocol.e("e")
        val msgs = ShadowLog.getLogs().filter { it.tag == "SendSpin.Protocol" }.map { it.msg }
        assertEquals(listOf("v", "d", "i", "w", "e"), msgs)
    }

    @Test
    fun `session start end emit INFO markers via App category`() {
        AppLog.setLevel(LogLevel.INFO)
        AppLog.session.start("MyServer", "192.168.1.10")
        AppLog.session.end()
        val appLogs = ShadowLog.getLogs().filter { it.tag == "SendSpin.App" }.map { it.msg }
        assertTrue("session start message present: $appLogs", appLogs.any { it.contains("MyServer") })
        assertTrue("session end message present: $appLogs", appLogs.any { it.contains("ended", ignoreCase = true) })
    }

    @Test
    fun `preference migration - old true maps to DEBUG and old key is removed`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putBoolean("debug_logging_enabled", true).commit()
        prefs.edit().remove("log_level").commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.DEBUG, AppLog.level)
        assertFalse("old pref should be removed", prefs.contains("debug_logging_enabled"))
        assertEquals("DEBUG", prefs.getString("log_level", null))
    }

    @Test
    fun `preference migration - old false maps to OFF`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putBoolean("debug_logging_enabled", false).commit()
        prefs.edit().remove("log_level").commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.OFF, AppLog.level)
        assertFalse(prefs.contains("debug_logging_enabled"))
    }

    @Test
    fun `preference migration - existing new key wins over old`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit()
            .putBoolean("debug_logging_enabled", true)
            .putString("log_level", "WARN")
            .commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.WARN, AppLog.level)
        assertFalse(prefs.contains("debug_logging_enabled"))
    }

    @Test
    fun `setLevel persists to preferences`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(ctx)
        AppLog.setLevel(LogLevel.INFO)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        assertEquals("INFO", prefs.getString("log_level", null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.AppLogTest"`
Expected: FAIL. Compilation error: `AppLog` unresolved reference.

- [ ] **Step 3: Write the implementation**

File: `android/app/src/main/java/com/sendspindroid/logging/AppLog.kt`

```kotlin
package com.sendspindroid.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Public facade for on-device logging.
 *
 * Call sites use category-scoped loggers:
 * ```
 * AppLog.Audio.d("chunk queued")
 * AppLog.Protocol.w("bad message type")
 * AppLog.Network.e("dropped", exception)
 * ```
 *
 * Level is global ([level]), set by the user in Settings. Gating happens in [Logger.x] before the
 * `android.util.Log` call. The [LogcatBridge] is the sole writer to the file system; facade calls
 * do not touch disk directly.
 */
object AppLog {

    private const val PREF_LOG_LEVEL = "log_level"
    private const val PREF_LEGACY_DEBUG_LOGGING = "debug_logging_enabled"

    @Volatile
    var level: LogLevel = LogLevel.OFF
        private set

    @Volatile
    internal var writer: LogFileWriter? = null
        private set

    // Bridge is wired in Task 6.
    @Volatile
    internal var bridge: LogcatBridge? = null

    val Audio: Logger = Logger(LogCategory.Audio)
    val Sync: Logger = Logger(LogCategory.Sync)
    val Protocol: Logger = Logger(LogCategory.Protocol)
    val Network: Logger = Logger(LogCategory.Network)
    val Playback: Logger = Logger(LogCategory.Playback)
    val MusicAssistant: Logger = Logger(LogCategory.MusicAssistant)
    val Remote: Logger = Logger(LogCategory.Remote)
    val UI: Logger = Logger(LogCategory.UI)
    val App: Logger = Logger(LogCategory.App)

    /** Session markers for connect/disconnect events. */
    object session {
        fun start(serverName: String, serverAddress: String) {
            App.i("Session started: $serverName ($serverAddress)")
        }

        fun end() {
            App.i("Session ended")
        }
    }

    /**
     * Initialize the logger. Call once at app startup from [com.sendspindroid.MainActivity].
     *
     * Creates the log directory + active file, runs the one-time preference migration from the
     * legacy `debug_logging_enabled` boolean to the new `log_level` string, and applies the
     * resulting level. Bridge startup is handled in [setLevel].
     */
    fun init(context: Context) {
        val logsDir = File(context.cacheDir, "logs")
        val w = LogFileWriter(logsDir)
        w.init()
        writer = w

        // Also clean up the legacy single-file location if present.
        File(context.cacheDir, "debug.log").takeIf { it.exists() }?.delete()

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(PREF_LOG_LEVEL, null)
        val resolved: LogLevel = when {
            stored != null -> runCatching { LogLevel.valueOf(stored) }.getOrDefault(LogLevel.OFF)
            prefs.contains(PREF_LEGACY_DEBUG_LOGGING) -> {
                if (prefs.getBoolean(PREF_LEGACY_DEBUG_LOGGING, false)) LogLevel.DEBUG else LogLevel.OFF
            }
            else -> LogLevel.OFF
        }
        // Persist & strip legacy key regardless of which branch we took, so future runs skip this block.
        prefs.edit()
            .putString(PREF_LOG_LEVEL, resolved.name)
            .remove(PREF_LEGACY_DEBUG_LOGGING)
            .apply()

        setLevel(resolved)
    }

    /**
     * Change the global log level. Persists to preferences. Bridge start/stop happens here in Task 6.
     */
    fun setLevel(newLevel: LogLevel) {
        level = newLevel
        writer?.let { _ ->
            // Write an audit trail entry so shared logs show when the user adjusted the level.
            if (newLevel != LogLevel.OFF) {
                App.i("Log level set to ${newLevel.name}")
            }
        }
        // Persist only when we have a context-aware writer; during tests we call setLevel directly.
        // Bridge transition wiring is added in Task 6.
    }

    /** For Settings UI. Returns the total size (KB) and file count across rotated files. */
    fun logFileStats(): Pair<Long, Int> {
        val w = writer ?: return 0L to 0
        val files = w.currentFiles()
        val totalBytes = files.sumOf { it.length() }
        return (totalBytes / 1024L) to files.size
    }

    /** Create a share intent with a concatenated log file. */
    fun shareIntent(context: Context): Intent? = writer?.shareIntent(context)

    /** Clear all rotated log files. */
    fun clear() {
        writer?.clear()
    }
}

/**
 * Per-category logger. One instance exists as a property on [AppLog] per [LogCategory].
 * All methods are gate-checked against [AppLog.level] before delegating to [android.util.Log].
 */
class Logger internal constructor(private val category: LogCategory) {

    fun v(msg: String) {
        if (AppLog.level.permits(LogLevel.VERBOSE)) Log.v(category.tag, msg)
    }

    fun d(msg: String) {
        if (AppLog.level.permits(LogLevel.DEBUG)) Log.d(category.tag, msg)
    }

    fun i(msg: String) {
        if (AppLog.level.permits(LogLevel.INFO)) Log.i(category.tag, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (AppLog.level.permits(LogLevel.WARN)) {
            if (t != null) Log.w(category.tag, msg, t) else Log.w(category.tag, msg)
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (AppLog.level.permits(LogLevel.ERROR)) {
            if (t != null) Log.e(category.tag, msg, t) else Log.e(category.tag, msg)
        }
    }
}
```

Now fix `setLevel` so persistence to prefs actually runs. We need a `Context` for prefs. Add a stored `appContext` field and update `init`:

Add near the top of `AppLog`:

```kotlin
    @Volatile
    private var appContext: Context? = null
```

Update `init(context)` to save the context:

```kotlin
fun init(context: Context) {
    appContext = context.applicationContext
    // ... rest unchanged
}
```

Update `setLevel` to persist:

```kotlin
fun setLevel(newLevel: LogLevel) {
    level = newLevel
    appContext?.let { ctx ->
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_LOG_LEVEL, newLevel.name)
            .apply()
    }
    if (newLevel != LogLevel.OFF) {
        App.i("Log level set to ${newLevel.name}")
    }
    // Bridge transition wiring is added in Task 6.
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.AppLogTest"`
Expected: BUILD SUCCESSFUL, all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/AppLog.kt app/src/test/java/com/sendspindroid/logging/AppLogTest.kt
git commit -m "feat(logging): add AppLog facade with session markers and pref migration"
```

---

## Task 5: `LogcatBridge` -- subprocess reader

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/logging/LogcatBridge.kt`

No unit test: spawning a real `logcat` process from a JVM-only unit test is not possible; end-to-end coverage is in Task 6 via an instrumented test.

- [ ] **Step 1: Write the implementation**

File: `android/app/src/main/java/com/sendspindroid/logging/LogcatBridge.kt`

```kotlin
package com.sendspindroid.logging

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the `logcat` subprocess filtered to the app's own PID and streams each line to
 * [LogFileWriter]. Manages the subprocess lifecycle as a function of the current [LogLevel].
 *
 * When [level] is [LogLevel.OFF], the bridge is fully stopped (no subprocess, no coroutines).
 * Level changes restart the subprocess with the new priority filter.
 *
 * If the subprocess dies unexpectedly, the reader writes a marker line and retries with 1s backoff,
 * capped at 3 retries per 60s window. After the cap the bridge gives up silently; the app's own
 * [AppLog] facade calls still work via plain `android.util.Log`.
 */
internal class LogcatBridge(
    private val writer: LogFileWriter,
    private val scope: CoroutineScope,
) {
    private val stateLock = Any()
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var process: Process? = null

    fun start(level: LogLevel) {
        if (level == LogLevel.OFF) return
        synchronized(stateLock) {
            if (readerJob?.isActive == true) return
            spawn(level)
        }
    }

    fun stop() {
        synchronized(stateLock) {
            try {
                process?.destroy()
            } catch (_: Exception) { /* best-effort */ }
            process = null
            // Join briefly; avoid deadlocking the main thread if caller runs on it.
            runBlocking {
                try {
                    readerJob?.cancelAndJoin()
                    stderrJob?.cancelAndJoin()
                } catch (_: Exception) { /* ignore */ }
            }
            readerJob = null
            stderrJob = null
        }
    }

    fun setLevel(level: LogLevel) {
        if (level == LogLevel.OFF) {
            stop()
        } else {
            stop()
            start(level)
        }
    }

    private fun spawn(level: LogLevel) {
        val pid = Process.myPid().toString()
        val priority = level.logcatPriorityChar()
        val cmd = listOf("logcat", "-v", "threadtime", "--pid=$pid", "-T", "1", "*:$priority")

        val restartWindow = 60_000L
        val maxRestarts = 3

        readerJob = scope.launch(Dispatchers.IO) {
            var restartTimestamps = mutableListOf<Long>()
            while (isActive) {
                val proc = try {
                    ProcessBuilder(cmd).redirectErrorStream(false).start()
                } catch (t: Throwable) {
                    writer.appendLine("[bridge] failed to spawn logcat: ${t.message}")
                    return@launch
                }
                process = proc

                stderrJob = launch(Dispatchers.IO) {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                            for (line in lines) {
                                if (!isActive) break
                                writer.appendLine("[logcat-stderr] $line")
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }
                }

                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                        for (line in lines) {
                            if (!isActive) return@launch
                            writer.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                    // fall through to restart logic
                }

                if (!isActive) return@launch

                writer.appendLine("[bridge] logcat process ended unexpectedly, restarting")

                val now = System.currentTimeMillis()
                restartTimestamps = restartTimestamps.filter { now - it < restartWindow }.toMutableList()
                if (restartTimestamps.size >= maxRestarts) {
                    writer.appendLine("[bridge] giving up after $maxRestarts restart attempts in ${restartWindow / 1000}s")
                    return@launch
                }
                restartTimestamps.add(now)
                delay(1000)
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/LogcatBridge.kt
git commit -m "feat(logging): add LogcatBridge subprocess reader"
```

---

## Task 6: Wire `LogcatBridge` into `AppLog` + instrumented test

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/logging/AppLog.kt`
- Create: `android/app/src/androidTest/java/com/sendspindroid/logging/LogcatBridgeInstrumentedTest.kt`

The instrumented test creates its own small `LogFileWriter` + `LogcatBridge` and verifies a `Log.d` call makes it into the file. It does NOT touch `AppLog.init` (which mutates singleton state).

- [ ] **Step 1: Wire bridge transitions into `AppLog`**

In `AppLog.kt`:

Replace the `init(context)` body to also construct the bridge and start it if needed. Replace these existing lines at the end of `init`:

```kotlin
setLevel(resolved)
```

With:

```kotlin
val br = LogcatBridge(w, kotlinx.coroutines.GlobalScope)
bridge = br
setLevel(resolved)
```

And the `import` section should add (top of file, with the other imports):

```kotlin
@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
```

Update `setLevel` to transition the bridge:

```kotlin
fun setLevel(newLevel: LogLevel) {
    val previous = level
    level = newLevel
    appContext?.let { ctx ->
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_LOG_LEVEL, newLevel.name)
            .apply()
    }
    bridge?.let { br ->
        if (previous == LogLevel.OFF && newLevel != LogLevel.OFF) br.start(newLevel)
        else if (newLevel == LogLevel.OFF && previous != LogLevel.OFF) br.stop()
        else if (newLevel != LogLevel.OFF) br.setLevel(newLevel)
    }
    if (newLevel != LogLevel.OFF) {
        App.i("Log level set to ${newLevel.name}")
    }
}
```

- [ ] **Step 2: Verify unit tests still pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.logging.*"`
Expected: BUILD SUCCESSFUL. AppLogTest still passes because the bridge is null in test setup (tests never call `init()` except for the pref-migration tests, and those don't assert bridge behavior).

Note: The `@file:OptIn(DelicateCoroutinesApi::class)` silences the `GlobalScope` warning. If it causes a test build failure, fall back to a dedicated scope:

```kotlin
private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

then pass `bridgeScope` instead of `GlobalScope`. This requires adding imports for `SupervisorJob`, `Dispatchers`, `CoroutineScope`. Use this approach if lint forbids `@file:OptIn` for `DelicateCoroutinesApi`.

- [ ] **Step 3: Write the instrumented test**

File: `android/app/src/androidTest/java/com/sendspindroid/logging/LogcatBridgeInstrumentedTest.kt`

```kotlin
package com.sendspindroid.logging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LogcatBridgeInstrumentedTest {

    private lateinit var tempDir: File
    private lateinit var writer: LogFileWriter
    private lateinit var scope: CoroutineScope
    private lateinit var bridge: LogcatBridge

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(ctx.cacheDir, "logbridge-test-${System.nanoTime()}")
        tempDir.mkdirs()
        writer = LogFileWriter(tempDir, maxFiles = 3, maxBytesPerFile = 10 * 1024 * 1024)
        writer.init()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridge = LogcatBridge(writer, scope)
    }

    @After
    fun tearDown() {
        bridge.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun bridge_captures_logd_calls_into_file() {
        bridge.start(LogLevel.DEBUG)
        val probe = "bridge-probe-${UUID.randomUUID()}"
        Log.d("SendSpin.TestProbe", probe)

        // Poll up to 5 seconds (logcat has some delivery lag on CI emulators).
        val deadline = System.currentTimeMillis() + 5_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            val content = writer.currentFiles().joinToString("\n") { it.readText() }
            if (content.contains(probe)) {
                found = true
            } else {
                Thread.sleep(200)
            }
        }
        assertTrue("probe line should appear in log file within 5s", found)
    }

    @Test
    fun bridge_stop_halts_capture() {
        bridge.start(LogLevel.DEBUG)
        Thread.sleep(500)
        bridge.stop()
        Thread.sleep(200)

        // Snapshot file contents before emitting the post-stop probe.
        val baselineSize = writer.currentFiles().sumOf { it.length() }
        val probe = "bridge-poststop-${UUID.randomUUID()}"
        Log.d("SendSpin.TestProbe", probe)

        Thread.sleep(2_000)
        val postSize = writer.currentFiles().sumOf { it.length() }
        val content = writer.currentFiles().joinToString("\n") { it.readText() }

        // Post-stop writes should not reach the file. Allow tiny fluctuation from any in-flight
        // buffered line that flushed after stop().
        assertFalse("probe written after stop must not appear", content.contains(probe))
        assertTrue("file must not grow materially after stop", (postSize - baselineSize) < 1024)
    }
}
```

- [ ] **Step 4: Run the instrumented test**

Requires a running emulator or connected device.

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest --tests "com.sendspindroid.logging.LogcatBridgeInstrumentedTest"`
Expected: BUILD SUCCESSFUL, both tests pass.

**If no emulator is available at plan-execution time:** skip this step and annotate the test as `@Ignore` with a TODO comment `"requires emulator"`. This is acceptable -- the bridge will be smoke-tested manually in Task 12.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/logging/AppLog.kt app/src/androidTest/java/com/sendspindroid/logging/LogcatBridgeInstrumentedTest.kt
git commit -m "feat(logging): wire LogcatBridge into AppLog, add instrumented test"
```

---

## Task 7: `MainActivity` migration

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

Replace `FileLogger.init + DebugLogger.isEnabled = prefs.getBoolean(...)` with `AppLog.init(this)`. Replace `FileLogger.i(...)` with `AppLog.App.i(...)`.

- [ ] **Step 1: Make the edits**

Near the top of `MainActivity.kt`, replace:

```kotlin
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.debug.FileLogger
```

With:

```kotlin
import com.sendspindroid.logging.AppLog
```

In `onCreate`, replace lines 499-506 (the block starting with `// Initialize file-based debug logger...`) with:

```kotlin
        // Initialize on-device logging facade. This also runs one-time pref migration from the
        // legacy `debug_logging_enabled` flag to the new `log_level` string.
        AppLog.init(this)
        AppLog.App.i("MainActivity onCreate (log level: ${AppLog.level})")
```

Remove the now-unused `val prefs = PreferenceManager.getDefaultSharedPreferences(this)` line IF it's no longer referenced elsewhere in `onCreate` (keep it otherwise). Grep the file to confirm.

- [ ] **Step 2: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If unused-import warnings appear for `androidx.preference.PreferenceManager`, remove them.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/MainActivity.kt
git commit -m "refactor(logging): migrate MainActivity to AppLog"
```

---

## Task 8: `PlaybackService` migration

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Three changes:
1. Replace `DebugLogger.startSession / endSession / logStats / isEnabled` with `AppLog.session.*` and `AppLog.Audio.d(...)`.
2. Update the broadcast receiver to listen on `ACTION_LOG_LEVEL_CHANGED` and key off `AppLog.level != OFF` instead of a boolean.
3. Update import block.

Note: `SettingsViewModel` still uses the old constant `ACTION_DEBUG_LOGGING_CHANGED` at this point -- the PlaybackService receiver needs to switch first so the new broadcast constant must be defined somewhere both files can reach. The simplest order: keep BOTH receivers briefly. Actually simpler: update both the ViewModel broadcast and PlaybackService receiver in THIS task, since they're a pair.

Revised: this task also updates the two broadcast constants in `SettingsViewModel` (leaving the rest of that file for Task 10).

- [ ] **Step 1: Update broadcast constants in `SettingsViewModel.kt`**

In `SettingsViewModel.kt` companion object, replace:

```kotlin
        const val ACTION_DEBUG_LOGGING_CHANGED = "com.sendspindroid.ACTION_DEBUG_LOGGING_CHANGED"
        const val EXTRA_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
```

With:

```kotlin
        const val ACTION_LOG_LEVEL_CHANGED = "com.sendspindroid.ACTION_LOG_LEVEL_CHANGED"
        const val EXTRA_LOG_LEVEL = "log_level"
```

Update the broadcaster in `setDebugLogging` (line ~250) temporarily to use the new action name with a string extra:

```kotlin
        val intent = Intent(ACTION_LOG_LEVEL_CHANGED).apply {
            putExtra(EXTRA_LOG_LEVEL, if (enabled) "DEBUG" else "OFF")
        }
```

Note: this keeps `setDebugLogging` working so the file compiles; the full ViewModel rewrite happens in Task 10.

- [ ] **Step 2: Update `PlaybackService.kt` imports and receiver**

In `PlaybackService.kt`, replace the import:

```kotlin
import com.sendspindroid.debug.DebugLogger
```

With:

```kotlin
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
```

Replace the `debugLoggingReceiver` block (lines ~226-240):

```kotlin
    // BroadcastReceiver for log level changes from settings
    private val logLevelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val levelStr = intent.getStringExtra(SettingsViewModel.EXTRA_LOG_LEVEL) ?: return
            val level = runCatching { LogLevel.valueOf(levelStr) }.getOrDefault(LogLevel.OFF)
            Log.i(TAG, "Log level changed: $level")

            if (level != LogLevel.OFF && isConnected()) {
                startDebugLogging()
            } else {
                stopDebugLogging()
            }
        }
    }
```

Replace the receiver registration (lines ~553-557):

```kotlin
        // Register receiver for log level changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logLevelReceiver,
            IntentFilter(SettingsViewModel.ACTION_LOG_LEVEL_CHANGED)
        )
```

Replace the receiver unregister (line ~3547):

```kotlin
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logLevelReceiver)
```

- [ ] **Step 3: Update session markers, stats gate, and stats body**

In `PlaybackService.kt`:

Line 333 (in some reconnection path), replace:

```kotlin
            if (DebugLogger.isEnabled && isConnected()) {
```

With:

```kotlin
            if (AppLog.level != LogLevel.OFF && isConnected()) {
```

Line ~799, replace:

```kotlin
                DebugLogger.startSession(serverName, serverAddr)
```

With:

```kotlin
                AppLog.session.start(serverName, serverAddr)
```

Line ~820, replace:

```kotlin
                DebugLogger.endSession()
```

With:

```kotlin
                AppLog.session.end()
```

In the `logCurrentStats()` method (line ~1875), replace:

```kotlin
        DebugLogger.logStats(syncStats)
```

With:

```kotlin
        AppLog.Audio.d("Stats: " +
            "state=${syncStats.playbackState.name}, " +
            "syncErr=${syncStats.syncErrorUs}us, " +
            "queue=${syncStats.queuedSamples}, " +
            "offset=${syncStats.clockOffsetUs}us, " +
            "insertN=${syncStats.insertEveryNFrames}, dropN=${syncStats.dropEveryNFrames}, " +
            "framesIns=${syncStats.framesInserted}, framesDrop=${syncStats.framesDropped}")
```

Line ~1882, replace:

```kotlin
        if (DebugLogger.isEnabled) {
```

With:

```kotlin
        if (AppLog.level != LogLevel.OFF) {
```

Line ~1834 comment, replace:

```kotlin
     * Logs the current stats to DebugLogger if enabled.
```

With:

```kotlin
     * Logs the current stats to AppLog if logging is enabled.
```

- [ ] **Step 4: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. SettingsViewModel still has its old `debugLogging` state and `setDebugLogging` function -- that's intentional; the full rewrite is Task 10.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/playback/PlaybackService.kt app/src/main/java/com/sendspindroid/ui/settings/SettingsViewModel.kt
git commit -m "refactor(logging): migrate PlaybackService and broadcast constants to AppLog"
```

---

## Task 9: `SyncAudioPlayer` full migration

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

There are ~70 `Log.x` calls using the tag `"SyncAudioPlayer"` and 5 `FileLogger.i(TAG, ...)` calls. All migrate to `AppLog.Audio.<level>` except sync-correction / start-gating messages, which go to `AppLog.Sync.<level>`.

**Mapping rule:**
- Messages that reference sync error, clock, start-gating, reanchoring, drift -> `AppLog.Sync`
- Everything else (AAudio callback, chunk queueing, decoder, playback state, errors) -> `AppLog.Audio`

- [ ] **Step 1: Update imports**

In `SyncAudioPlayer.kt`, remove:

```kotlin
import com.sendspindroid.debug.FileLogger
```

Add:

```kotlin
import com.sendspindroid.logging.AppLog
```

Keep the `import android.util.Log` import for now -- we'll remove it after migration if no references remain.

- [ ] **Step 2: Migrate all `FileLogger.i(TAG, ...)` calls**

All 5 existing `FileLogger.i` calls concern start-gating transitions. Replace each with `AppLog.Sync.i(...)`. The pattern:

```kotlin
// Before:
FileLogger.i(TAG, "DAC-aware start gating transition: ...")

// After:
AppLog.Sync.i("DAC-aware start gating transition: ...")
```

Apply to all 5 sites (around lines 1476, 1485, 1542, 1551, 1568, 1577).

- [ ] **Step 3: Migrate all raw `Log.x(TAG, ...)` calls**

For each `Log.v/d/i/w/e(TAG, ...)` call, replace with the corresponding `AppLog.<Category>.<level>(...)` call, dropping the `TAG` argument.

**Category selection rule:**

Use `AppLog.Sync` for lines whose message contains any of: `"sync"`, `"clock"`, `"start gating"`, `"reanchor"`, `"drift"`, `"kalman"`, `"converge"`, `"offset"`.
Use `AppLog.Audio` for everything else.

**Example transformations:**

```kotlin
// Before:
Log.d(TAG, "Chunk queued: ${chunk.size} bytes")
// After:
AppLog.Audio.d("Chunk queued: ${chunk.size} bytes")

// Before:
Log.w(TAG, "Sync error large: ${errorUs}us")
// After:
AppLog.Sync.w("Sync error large: ${errorUs}us")

// Before:
Log.e(TAG, "Failed to write frames", e)
// After:
AppLog.Audio.e("Failed to write frames", e)
```

**Finding all sites:** run this grep to enumerate the remaining calls and make sure none are missed:

```bash
cd android && grep -n 'Log\.[vdiwe](TAG,' app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
```

Do the transformation. Then re-run the grep -- expected: 0 matches.

- [ ] **Step 4: Remove the unused `TAG` constant and `Log` import (if unused)**

Grep for remaining `Log.` references in the file:

```bash
cd android && grep -n 'Log\.' app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt | grep -v 'AppLog'
```

If no hits, remove:
- `import android.util.Log`
- `private const val TAG = "SyncAudioPlayer"` in the companion object (only if no other code uses `TAG`; otherwise leave it).

- [ ] **Step 5: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run any existing SyncAudioPlayer-touching unit tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; no regressions. The existing test suite uses the `TAG` constant and `Log.x` indirectly -- since we replaced call sites but kept behavior identical, tests should pass.

- [ ] **Step 7: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
git commit -m "refactor(logging): migrate SyncAudioPlayer to AppLog"
```

---

## Task 10: Settings UI replacement (ViewModel + Screen + strings + SettingsActivity)

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsViewModel.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/SettingsActivity.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

Single commit because these four files must change together to compile.

- [ ] **Step 1: Update `strings.xml`**

In `android/app/src/main/res/values/strings.xml`, remove:

```xml
    <string name="pref_debug_logging_title">Debug Logging</string>
    <string name="pref_debug_logging_summary_off">Disabled - tap to enable debug logging</string>
    <string name="pref_debug_logging_summary_on">Enabled - log file: %d KB</string>
```

Add (place near the other debug-related strings, e.g. right after `pref_category_debug`):

```xml
    <string name="pref_log_level_title">Log Level</string>
    <string name="pref_log_level_summary">%1$s (%2$d KB, %3$d files)</string>
    <string name="log_level_off">Off</string>
    <string name="log_level_error">Error</string>
    <string name="log_level_warn">Warn</string>
    <string name="log_level_info">Info</string>
    <string name="log_level_debug">Debug</string>
    <string name="log_level_verbose">Verbose</string>
    <string name="pref_clear_logs_title">Clear Logs</string>
    <string name="pref_clear_logs_summary">Delete all stored log files</string>
```

- [ ] **Step 2: Rewrite the debug section of `SettingsViewModel.kt`**

In `SettingsViewModel.kt`:

Update imports -- add:

```kotlin
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
```

Remove:

```kotlin
import com.sendspindroid.debug.DebugLogger
```

Replace the debug-settings block (lines ~96-101):

```kotlin
    // Debug settings
    private val _debugLogging = MutableStateFlow(DebugLogger.isEnabled)
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    private val _debugSampleCount = MutableStateFlow(DebugLogger.getSampleCount())
    val debugSampleCount: StateFlow<Int> = _debugSampleCount.asStateFlow()
```

With:

```kotlin
    // Log level (global) and log file stats (KB, file count)
    private val _logLevel = MutableStateFlow(AppLog.level)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logFileStats = MutableStateFlow(AppLog.logFileStats())
    val logFileStats: StateFlow<Pair<Long, Int>> = _logFileStats.asStateFlow()
```

Replace the stats update loop (lines ~112-119):

```kotlin
    private fun startDebugStatsUpdates() {
        viewModelScope.launch {
            while (isActive) {
                _debugSampleCount.value = DebugLogger.getSampleCount()
                delay(DEBUG_STATS_UPDATE_INTERVAL_MS)
            }
        }
    }
```

With:

```kotlin
    private fun startDebugStatsUpdates() {
        viewModelScope.launch {
            while (isActive) {
                _logFileStats.value = AppLog.logFileStats()
                _logLevel.value = AppLog.level
                delay(DEBUG_STATS_UPDATE_INTERVAL_MS)
            }
        }
    }
```

Replace `setDebugLogging` (lines ~237-254):

```kotlin
    fun setDebugLogging(enabled: Boolean) {
        DebugLogger.isEnabled = enabled
        // Save to preferences
        prefs.edit().putBoolean("debug_logging_enabled", enabled).apply()
        _debugLogging.value = enabled

        if (!enabled) {
            DebugLogger.clear()
            _debugSampleCount.value = 0
        }

        // Broadcast to PlaybackService
        val intent = Intent(ACTION_LOG_LEVEL_CHANGED).apply {
            putExtra(EXTRA_LOG_LEVEL, if (enabled) "DEBUG" else "OFF")
        }
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }
```

With:

```kotlin
    fun setLogLevel(level: LogLevel) {
        AppLog.setLevel(level)
        _logLevel.value = level
        if (level == LogLevel.OFF) {
            _logFileStats.value = AppLog.logFileStats()
        }
        val intent = Intent(ACTION_LOG_LEVEL_CHANGED).apply {
            putExtra(EXTRA_LOG_LEVEL, level.name)
        }
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }

    fun clearLogs() {
        AppLog.clear()
        _logFileStats.value = AppLog.logFileStats()
    }
```

- [ ] **Step 3: Rewrite the debug section of `SettingsScreen.kt`**

In `SettingsScreen.kt`:

Update the state collection near the top. Replace:

```kotlin
    val debugLogging by viewModel.debugLogging.collectAsStateWithLifecycle()
    val debugSampleCount by viewModel.debugSampleCount.collectAsStateWithLifecycle()
```

With:

```kotlin
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logFileStats by viewModel.logFileStats.collectAsStateWithLifecycle()
```

Add import:

```kotlin
import com.sendspindroid.logging.LogLevel
```

Replace the Debug section (lines ~250-271):

```kotlin
            // Debug Category
            PreferenceCategory(title = stringResource(R.string.pref_category_debug))
            SwitchPreference(
                title = stringResource(R.string.pref_debug_logging_title),
                summary = if (debugLogging) {
                    stringResource(R.string.pref_debug_logging_summary_on, debugSampleCount)
                } else {
                    stringResource(R.string.pref_debug_logging_summary_off)
                },
                checked = debugLogging,
                onCheckedChange = { viewModel.setDebugLogging(it) }
            )
            TextPreference(
                title = stringResource(R.string.pref_export_logs_title),
                summary = if (debugSampleCount > 0) {
                    stringResource(R.string.pref_export_logs_summary)
                } else {
                    stringResource(R.string.pref_export_logs_summary_empty)
                },
                enabled = debugSampleCount > 0,
                onClick = onExportLogs
            )
```

With:

```kotlin
            // Debug Category
            PreferenceCategory(title = stringResource(R.string.pref_category_debug))

            // Log level selector (6-option segmented row)
            val levels = listOf(
                LogLevel.OFF to R.string.log_level_off,
                LogLevel.ERROR to R.string.log_level_error,
                LogLevel.WARN to R.string.log_level_warn,
                LogLevel.INFO to R.string.log_level_info,
                LogLevel.DEBUG to R.string.log_level_debug,
                LogLevel.VERBOSE to R.string.log_level_verbose,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.pref_log_level_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { index, (lvl, labelRes) ->
                        SegmentedButton(
                            selected = logLevel == lvl,
                            onClick = { viewModel.setLogLevel(lvl) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size)
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val (sizeKb, fileCount) = logFileStats
                Text(
                    text = stringResource(
                        R.string.pref_log_level_summary,
                        stringResource(levels.first { it.first == logLevel }.second),
                        sizeKb,
                        fileCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextPreference(
                title = stringResource(R.string.pref_export_logs_title),
                summary = if (logFileStats.first > 0) {
                    stringResource(R.string.pref_export_logs_summary)
                } else {
                    stringResource(R.string.pref_export_logs_summary_empty)
                },
                enabled = logFileStats.first > 0,
                onClick = onExportLogs
            )

            TextPreference(
                title = stringResource(R.string.pref_clear_logs_title),
                summary = stringResource(R.string.pref_clear_logs_summary),
                enabled = logFileStats.first > 0,
                onClick = { viewModel.clearLogs() }
            )
```

- [ ] **Step 4: Update `SettingsActivity.kt`**

In `SettingsActivity.kt`:

Replace import:

```kotlin
import com.sendspindroid.debug.DebugLogger
```

With:

```kotlin
import com.sendspindroid.logging.AppLog
```

Replace `exportDebugLogs()` body (lines 37-54):

```kotlin
    private fun exportDebugLogs() {
        val shareIntent = AppLog.shareIntent(this)

        if (shareIntent != null) {
            val chooserIntent = Intent.createChooser(
                shareIntent,
                getString(R.string.debug_share_chooser_title)
            )
            startActivity(chooserIntent)
            Toast.makeText(this, R.string.debug_log_exported, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.debug_log_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
```

- [ ] **Step 5: Verify compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all unit tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. The old `DebugLoggerSessionFieldsTest` and `FileLoggerConcurrencyTest` still exist and should still pass (they reference the old classes, which are still present until Task 11).

- [ ] **Step 7: Commit**

```bash
cd android && git add app/src/main/java/com/sendspindroid/ui/settings/SettingsViewModel.kt app/src/main/java/com/sendspindroid/ui/settings/SettingsScreen.kt app/src/main/java/com/sendspindroid/SettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(logging): replace debug toggle with 6-level segmented control"
```

---

## Task 11: Delete legacy `debug/` package

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/debug/FileLogger.kt`
- Delete: `android/app/src/main/java/com/sendspindroid/debug/DebugLogger.kt`
- Delete: `android/app/src/test/java/com/sendspindroid/debug/FileLoggerConcurrencyTest.kt`
- Delete: `android/app/src/test/java/com/sendspindroid/debug/DebugLoggerSessionFieldsTest.kt`

- [ ] **Step 1: Verify no remaining references to `debug/FileLogger` or `debug/DebugLogger`**

Run:

```bash
cd android && grep -rn 'com.sendspindroid.debug' app/src/main app/src/test 2>/dev/null
```

Expected: no matches. If any appear, they indicate a missed migration -- fix those references before deletion (likely in `SyncStats` or `model/` if stats logging still leaks).

Also check for bare `FileLogger`/`DebugLogger` references (without the full package):

```bash
cd android && grep -rn '\bFileLogger\b\|\bDebugLogger\b' app/src/main app/src/test 2>/dev/null
```

Expected: no matches.

- [ ] **Step 2: Delete the four files**

```bash
cd android && rm app/src/main/java/com/sendspindroid/debug/FileLogger.kt
rm app/src/main/java/com/sendspindroid/debug/DebugLogger.kt
rm app/src/test/java/com/sendspindroid/debug/FileLoggerConcurrencyTest.kt
rm app/src/test/java/com/sendspindroid/debug/DebugLoggerSessionFieldsTest.kt
```

- [ ] **Step 3: Delete empty `debug/` directories**

```bash
cd android && rmdir app/src/main/java/com/sendspindroid/debug 2>/dev/null
rmdir app/src/test/java/com/sendspindroid/debug 2>/dev/null
```

(If `rmdir` fails because the directories aren't empty, some tests were missed -- recheck Step 1.)

- [ ] **Step 4: Verify compile and all tests**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd android && git add -A app/src/main/java/com/sendspindroid/debug app/src/test/java/com/sendspindroid/debug
git commit -m "chore(logging): remove legacy FileLogger and DebugLogger"
```

---

## Task 12: Final build + manual smoke test

No code changes. This is a verification checkpoint.

- [ ] **Step 1: Full debug build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full unit test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. All tests pass (including pre-existing tests unrelated to logging).

- [ ] **Step 3: Instrumented tests (if emulator available)**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL. If no emulator, skip.

- [ ] **Step 4: Manual smoke test on device**

Install the debug build on a connected device:

```bash
cd android && ./gradlew installDebug
```

Then manually verify:

1. **Launch the app** -- no crash on startup. Open Settings -> Debug. Confirm the segmented control is visible with "Off" selected by default (or the previously-migrated level if a prior install had debug logging enabled).
2. **Select "Debug"** on the segmented row. File size indicator should start growing within a few seconds (connect to a SendSpin server if available to generate audio log traffic).
3. **Connect to a server and play for ~30 seconds.** Return to Settings. Confirm file size (KB) has increased and file count is 1 or 2.
4. **Tap "Export Logs".** Share sheet opens. Share to email/Gmail/Drive -- confirm a single concatenated log file is attached. Open the file and confirm it contains a mix of `SendSpin.Audio`, `SendSpin.Sync`, `SendSpin.Playback` entries AND raw tags from the rest of the app (e.g. `MainActivity`, `SyncAudioPlayer`).
5. **Tap "Clear Logs".** Confirm file size drops to ~0 KB.
6. **Select "Off".** Wait 10 seconds. Confirm file size does not grow.

If any step fails, document the observation and open a follow-up task.

- [ ] **Step 5: (If appropriate) push branch / open PR**

This is outside the scope of the plan -- handled per project workflow.

---

## Self-Review (done at plan-write time)

**1. Spec coverage:**

| Spec section              | Tasks covering it                |
|---------------------------|----------------------------------|
| LogLevel enum             | Task 1                           |
| LogCategory enum          | Task 1                           |
| AppLog facade + Logger    | Task 4                           |
| Session markers           | Task 4                           |
| LogcatBridge              | Task 5, 6                        |
| LogFileWriter rotation    | Task 2                           |
| LogFileWriter share       | Task 3                           |
| Preference migration      | Task 4                           |
| FileProvider config       | Unchanged -- verified in preamble |
| MainActivity wiring       | Task 7                           |
| PlaybackService migration | Task 8                           |
| SyncAudioPlayer migration | Task 9                           |
| Settings UI replacement   | Task 10                          |
| Legacy file deletion      | Task 11                          |
| Tests (LogFileWriter)     | Task 2, 3                        |
| Tests (AppLog)            | Task 4                           |
| Instrumented bridge test  | Task 6                           |
| Manual smoke              | Task 12                          |

No gaps.

**2. Placeholder scan:** No "TODO", "implement later", or "similar to task N" references. All code blocks show exact content.

**3. Type consistency:**
- `LogLevel.permits(callLevel)` defined in Task 1, used consistently in Task 4.
- `LogCategory.tag` defined in Task 1, referenced in Task 4 and Task 5.
- `LogFileWriter.appendLine`, `clear`, `currentFiles`, `shareIntent` defined in Task 2, used in Task 5 (bridge), Task 4 (facade), Task 6 (instrumented test).
- `AppLog.setLevel`, `AppLog.clear`, `AppLog.shareIntent`, `AppLog.logFileStats`, `AppLog.session.start`, `AppLog.session.end` defined in Task 4, used in Task 10 (Settings).
- `ACTION_LOG_LEVEL_CHANGED` + `EXTRA_LOG_LEVEL` defined in Task 8 (SettingsViewModel), used in Task 8 (PlaybackService receiver) and Task 10 (updated broadcaster).

All signatures consistent.
