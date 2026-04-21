# On-Device Logging Redesign

**Date:** 2026-04-20
**Status:** Design approved, pending implementation plan
**Scope:** Android app (`android/app/src/main/java/com/sendspindroid/`)

## Problem

On-device logging has two gaps:

1. **Capture coverage is near-empty.** The app has 885 calls to `android.util.Log.d/i/w/e` across 46 files, but only 7 files route through the current `FileLogger`/`DebugLogger`. When a user enables debug logging and shares the resulting file, the file is mostly empty relative to what the app actually did. Remote debugging is therefore ineffective.
2. **No structure or runtime control.** Log verbosity is a single enabled/disabled flag. There is no way to ask for "audio debug only" or "info and above for everything" without editing source and reinstalling.

Sharing UX is acceptable and is explicitly out of scope.

## Non-Goals

- Structured key/value logging
- Remote log shipping / telemetry
- Crash-capture integration
- Per-category level control (single global level only, per user decision)
- Lazy-evaluation log overloads (hot paths hand-guarded where needed)

## Solution Overview

A hybrid architecture with three collaborators:

```
Call sites (facade or raw android.util.Log)
           |
           v
  android logcat (ring buffer)
           |
           v
  LogcatBridge  --(own PID, level filter)-->  LogFileWriter  -->  cacheDir/logs/*.txt
```

- **`AppLog`** -- facade with per-category sub-loggers (`AppLog.Audio.d(...)`). Delegates to `android.util.Log` with a prefixed tag. No direct file I/O.
- **`LogcatBridge`** -- background reader. Spawns `logcat --pid=<self>` as a subprocess, streams stdout to `LogFileWriter`. Sole writer to disk for log lines.
- **`LogFileWriter`** -- file I/O owner. Rotation (up to 10 x 1 MB), concatenation on share, clear.

**Capture path: bridge-only (Option A).** `AppLog.X.d(...)` only calls `Log.d(...)`; the bridge is the single file writer. This means one code path, no dedup logic, and zero-refactor coverage for the 885 existing `Log.x` call sites.

## Component Design

### `LogLevel` (public enum)

```kotlin
package com.sendspindroid.logging

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, OFF }
```

Used both by the facade gate and as input to the logcat subprocess filter.

### `LogCategory` (public enum)

```kotlin
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

**Contract:** every `tag` value starts with `SendSpin.` -- this makes facade output trivially greppable in logcat and in the shared log file. The bridge itself filters by *level* (logcat's `*:<priority>` flag), not by tag, so raw `Log.x` calls from elsewhere in the app are still captured regardless of their tag.

**Package-to-category mapping** (for opportunistic migration; included as a KDoc table on `LogCategory`):

| Package                                | Category         |
|----------------------------------------|------------------|
| `sendspin/`, `sendspin/decoder/`       | `Audio`          |
| `sendspin/` (clock sync code)          | `Sync`           |
| `sendspin/protocol/`                   | `Protocol`       |
| `network/`, `discovery/`               | `Network`        |
| `playback/`                            | `Playback`       |
| `musicassistant/`                      | `MusicAssistant` |
| `remote/`                              | `Remote`         |
| `ui/`                                  | `UI`             |
| root, settings, boot receiver, etc.    | `App`            |

### `AppLog` (public facade)

```kotlin
object AppLog {
    @Volatile var level: LogLevel = LogLevel.OFF
        private set

    fun init(context: Context)
    fun setLevel(level: LogLevel)
    fun shareIntent(context: Context): Intent?
    fun clear()

    val Audio: Logger          = Logger(LogCategory.Audio)
    val Sync: Logger           = Logger(LogCategory.Sync)
    val Protocol: Logger       = Logger(LogCategory.Protocol)
    val Network: Logger        = Logger(LogCategory.Network)
    val Playback: Logger       = Logger(LogCategory.Playback)
    val MusicAssistant: Logger = Logger(LogCategory.MusicAssistant)
    val Remote: Logger         = Logger(LogCategory.Remote)
    val UI: Logger             = Logger(LogCategory.UI)
    val App: Logger            = Logger(LogCategory.App)

    object session {
        fun start(serverName: String, serverAddress: String)
        fun end()
    }
}

class Logger internal constructor(private val category: LogCategory) {
    fun v(msg: String)
    fun d(msg: String)
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)
}
```

**Call-site ergonomics:**

```kotlin
AppLog.Audio.d("chunk queued: ${chunk.size} bytes")
AppLog.Protocol.w("unexpected message type: $type")
AppLog.Network.e("websocket dropped", exception)
AppLog.session.start(server.name, server.address)
```

**`init(context)` responsibilities:**

1. Construct a `LogFileWriter(cacheDir/logs)`.
2. Construct a `LogcatBridge(writer)`.
3. Run one-time preference migration: if `debug_logging_enabled` exists, convert to `log_level` (true -> DEBUG, false -> OFF), then remove the old key.
4. Read `log_level` preference and apply via `setLevel(...)`.

**`setLevel(level)`:** stores the level (`@Volatile` write), persists to prefs, and transitions the bridge: `OFF -> other` calls `bridge.start(level)`; `other -> OFF` calls `bridge.stop()`; other transitions call `bridge.setLevel(level)` (implemented as stop + start with new filter).

### `LogcatBridge` (internal)

```kotlin
internal class LogcatBridge(
    private val writer: LogFileWriter,
    private val scope: CoroutineScope,
) {
    fun start(level: LogLevel)
    fun stop()
    fun setLevel(level: LogLevel)
}
```

**Subprocess command:**

```
logcat -v threadtime --pid=<self> -T 1 *:<minPriority>
```

- `-v threadtime` -- output format `MM-DD HH:MM:SS.mmm PID TID LEVEL/TAG: msg` (matches current `FileLogger` output).
- `--pid=<self>` -- our PID only, no system/other-app leakage.
- `-T 1` -- start from now, don't replay old ring buffer.
- `*:<priority>` -- `LogLevel` -> logcat priority letter: `V->V, D->D, I->I, W->W, E->E`. (`OFF` -> bridge is stopped instead.)

**Threading:**

- One coroutine on `Dispatchers.IO` reads the subprocess's stdout via `BufferedReader.lineSequence()` and calls `writer.appendLine(line)` for each line.
- A second coroutine drains stderr and writes `[logcat-stderr] <line>` to the writer (so subprocess errors are captured, not silently swallowed).
- `stop()` destroys the subprocess and joins both coroutines.

**Robustness:**

- If the subprocess exits unexpectedly, the reader catches EOF/`IOException`, writes `"[bridge] logcat process ended unexpectedly, restarting"` to the file, sleeps 1 s, and restarts. Capped at 3 restarts per 60 s window.
- If the cap is exceeded, the bridge gives up silently. `AppLog` calls still invoke `Log.d` (no crash), but nothing new lands in the file until the user toggles level OFF->ON.
- **Bridge is fully stopped when level=OFF** -- subprocess destroyed, no battery impact, contract is "off = off".

### `LogFileWriter` (internal)

```kotlin
internal class LogFileWriter(
    private val dir: File,
    private val maxFiles: Int = 10,
    private val maxBytesPerFile: Long = 1 * 1024 * 1024,
) {
    fun init()
    fun appendLine(line: String)
    fun appendRaw(block: String)
    fun clear()
    fun currentFiles(): List<File>   // oldest -> newest
    fun shareIntent(ctx: Context): Intent?
}
```

**File layout:**

- Directory: `cacheDir/logs/`
- Active file: `sendspin-log-0.txt` (always -- freshest)
- Older files: `sendspin-log-1.txt` ... `sendspin-log-9.txt` (oldest)

**Rotation algorithm** (called under the write lock, triggered after `appendLine` when `file.length() >= maxBytesPerFile`):

1. Delete `sendspin-log-9.txt` if it exists.
2. Rename `log-8 -> log-9`, `log-7 -> log-8`, ..., `log-0 -> log-1` (descending rename chain).
3. Create fresh `log-0.txt` with header: `=== Rotated at <timestamp> ===`.
4. Previous active file's last line: `=== Previous file rotated, continues in log-1.txt ===` (written before the rotation begins, so the rotated file is self-terminating).

Rotation occurs at line boundaries -- a line is never split across two files.

**Thread-safety:** all file ops under `synchronized(writeLock)`. Single-producer (the bridge coroutine) in practice; lock exists for correctness against `clear()` and `shareIntent()` potentially running on other threads.

**Share-intent behavior (concatenated):**

- `shareIntent(ctx)` concatenates all existing files in age order (log-9 -> log-0) into a single temp file `cacheDir/logs/sendspin-log-combined.txt`.
- Returns an `ACTION_SEND` intent with one `FileProvider` URI for the combined file.
- Share message body: app version, device, Android version, server name (same as today's `buildShareMessage`).
- The temp combined file is overwritten on each share -- no growth over time.

**Migration from current `cache/debug.log`:**

- On `init()`, if `cacheDir/debug.log` exists, delete it. One-time cleanup, no backward-compat retention.

**FileProvider config change:**

- `file_provider_paths.xml` must expose `cache/logs/` -- one-line addition to the existing `<cache-path>` entries.

## Settings UI

Replaces the current Debug section in `SettingsActivity` / `SettingsScreen`.

```
Debug
  Log level
    [ Off  Error  Warn  Info  Debug  Verbose ]   <- Material 3 SegmentedButtonRow

  Log file size:  347 KB (3 files)

  [ Export Logs ]    [ Clear Logs ]
```

**Control:** Material 3 `SegmentedButtonRow` with six segments in the order `OFF, ERROR, WARN, INFO, DEBUG, VERBOSE` -- least-to-most-verbose, left-to-right. Labels are short (<= 7 chars) to fit on phone widths; on very narrow screens, Compose's built-in text auto-size / overflow handles truncation. Selection writes to `SharedPreferences` immediately, no save button.

**File size indicator:** text row `"X KB (N files)"`, polled every 2 s by the existing `SettingsViewModel.startDebugStatsUpdates()` loop.

**Clear Logs button:** invokes `AppLog.clear()`, re-initializes `log-0.txt` with a header.

**`SettingsViewModel` changes:**

Remove:
```kotlin
val debugLogging: StateFlow<Boolean>
fun setDebugLogging(enabled: Boolean)
```

Add:
```kotlin
val logLevel: StateFlow<LogLevel>
fun setLogLevel(level: LogLevel)
fun clearLogs()
```

Rename:
```kotlin
debugSampleCount -> logFileSizeKb         // "347 KB (3 files)"
```

**Preference keys:**
- Old: `"debug_logging_enabled"` (Boolean) -- migrated once, then deleted.
- New: `"log_level"` (String, `LogLevel.name`).

**Broadcast actions:**
- Old: `ACTION_DEBUG_LOGGING_CHANGED` + boolean `EXTRA_DEBUG_LOGGING_ENABLED`.
- New: `ACTION_LOG_LEVEL_CHANGED` + string `EXTRA_LOG_LEVEL` (serialized `LogLevel.name`).
- `PlaybackService` listener: currently gates `logStats()` on `DebugLogger.isEnabled`; update to gate on `AppLog.level != LogLevel.OFF`.

**String resources** (`strings.xml`):
- Remove `debug_logging_title`, `debug_logging_summary`.
- Add `log_level_title`, `log_level_summary`, six segment labels, `clear_logs_button_title`, `clear_logs_confirmation`.
- Keep `debug_share_chooser_title`, `debug_log_exported`, `debug_log_export_failed`.

## Data Flow

**Enable logging (OFF -> DEBUG):**

```
User taps "Debug" segment in Settings
  -> SettingsViewModel.setLogLevel(DEBUG)
  -> AppLog.setLevel(DEBUG)
       - persists "log_level" = "DEBUG"
       - bridge.start(DEBUG)
           - spawns `logcat -v threadtime --pid=<pid> -T 1 *:D`
           - launches reader + stderr coroutines
  -> broadcast ACTION_LOG_LEVEL_CHANGED
  -> PlaybackService receives, begins stats logging
```

**Log line flow (running):**

```
SyncAudioPlayer calls AppLog.Audio.d("chunk queued")
  -> Logger gates on AppLog.level (DEBUG permits DEBUG)
  -> android.util.Log.d("SendSpin.Audio", "chunk queued")
  -> kernel logcat ring buffer
  -> LogcatBridge subprocess stdout
  -> reader coroutine: writer.appendLine(formatted line)
  -> LogFileWriter: synchronized write to log-0.txt
  -> if size >= 1 MB, rotate
```

**Share logs:**

```
User taps "Export Logs"
  -> SettingsActivity.exportDebugLogs()
  -> AppLog.shareIntent(context)
     -> LogFileWriter.shareIntent(context)
         - concatenate log-9 ... log-0 -> log-combined.txt
         - build ACTION_SEND intent via FileProvider
  -> Intent.createChooser(...) launched
```

**Disable logging (DEBUG -> OFF):**

```
User taps "Off" segment
  -> AppLog.setLevel(OFF)
       - bridge.stop() destroys subprocess, joins coroutines
       - persists "log_level" = "OFF"
  -> broadcast ACTION_LOG_LEVEL_CHANGED
  -> PlaybackService stops stats logging
```

## Error Handling

**Logcat subprocess failures:**

- Reader catches `IOException`/EOF -> writes error marker line -> 1 s backoff -> restart (capped 3x / 60 s).
- If cap exceeded, bridge silently gives up; `AppLog` calls still hit `Log.d` (never crash). User recovery: toggle level OFF -> ON.

**File I/O failures:**

- `LogFileWriter` catches `IOException` in `appendLine` / rotation -- silently swallows (per current `FileLogger` behavior). Logging must never crash the app.
- If the writer cannot open/create `cacheDir/logs/log-0.txt` during `init()`, the writer operates in a no-op mode (logs in memory for session markers only). A one-line warning is written via `Log.w` directly.

**Preference migration:**

- If both old and new keys exist (shouldn't happen, but defensive): new key wins, old key is deleted.
- If neither exists: default to `LogLevel.OFF`.

## Migration of Existing Call Sites

**Principle: opportunistic, not big-bang.** The 885 raw `Log.x` calls continue to work via the bridge; their output goes to the file automatically once logging is enabled.

**Migrated in the initial PR:**

1. **`SyncAudioPlayer`** -- full migration of all ~70 `Log.x` and 5 `FileLogger.x` calls to `AppLog.Audio.x` / `AppLog.Sync.x` (split by topic: sync-correction messages -> `Sync`, playback/decoder -> `Audio`).
2. **`PlaybackService`** -- port `DebugLogger.startSession/endSession/logStats` to `AppLog.session.*` and `AppLog.Audio.d(...)` for the stats line. Update the broadcast receiver.
3. **`MainActivity`** -- `FileLogger.init(this)` -> `AppLog.init(this)`.

**Added files:**
- `logging/LogLevel.kt`
- `logging/LogCategory.kt`
- `logging/AppLog.kt`
- `logging/LogcatBridge.kt`
- `logging/LogFileWriter.kt`

**Deleted files:**
- `debug/FileLogger.kt`
- `debug/DebugLogger.kt`
- (empty `debug/` package deleted in same PR)

**Post-merge policy:** when editing a file for another reason, migrate its `Log.x` calls to `AppLog.<Category>.x`. No migration-only PRs -- they only churn git blame.

## Testing

**Unit tests** (`android/app/src/test/.../logging/`):

1. `LogFileWriterTest`
   - Rotation triggers at size threshold; `log-0` and `log-1` exist with expected sizes.
   - Rotation cap: 15 MB written -> exactly 10 files, oldest data lost.
   - Rotation marker: first line of freshly-rotated `log-0` matches the header pattern.
   - Line atomicity: 2000-char line near the boundary appears intact in exactly one file.
   - `clear()` removes all files, recreates `log-0.txt` with header.
   - `currentFiles()` returns oldest -> newest order.

2. `AppLogTest`
   - Level gating: level=WARN -> `Audio.d` no-op, `Audio.w` writes once.
   - Level=OFF: every method on every category is a no-op.
   - Tag prefix contract: every `LogCategory.tag` starts with `"SendSpin."`.
   - Preference migration: old `debug_logging_enabled=true` -> new `log_level=DEBUG`, old key removed; `=false` -> `log_level=OFF`.

**Instrumented test** (`android/app/src/androidTest/.../logging/`):

3. `LogcatBridgeInstrumentedTest`
   - Start bridge at DEBUG with a `LogFileWriter` in a `tempDir`.
   - Call `Log.d("SendSpin.TestProbe", "bridge-probe-<uuid>")`.
   - Poll file up to 2 s; assert UUID appears.
   - `setLevel(OFF)` -> wait for bridge stop -> another `Log.d` -> assert it does *not* appear.

**Out of scope for tests:**
- Compose UI rendering of the segmented button (manual inspection).
- `FileProvider` share-intent content (wrapper around Android SDK).
- Subprocess death/restart backoff timing (manual smoke: enable logging, `killall logcat` from adb, verify restart marker).

**Test infrastructure note:** verify during planning whether `src/test/` is already configured for this package. If not, add plain JUnit 4 + MockK for unit tests. Instrumented test uses existing `androidTest` setup and `ApplicationProvider`.

## File Change Summary

**New files (5):**
- `android/app/src/main/java/com/sendspindroid/logging/LogLevel.kt`
- `android/app/src/main/java/com/sendspindroid/logging/LogCategory.kt`
- `android/app/src/main/java/com/sendspindroid/logging/AppLog.kt`
- `android/app/src/main/java/com/sendspindroid/logging/LogcatBridge.kt`
- `android/app/src/main/java/com/sendspindroid/logging/LogFileWriter.kt`

**Deleted files (2):**
- `android/app/src/main/java/com/sendspindroid/debug/FileLogger.kt`
- `android/app/src/main/java/com/sendspindroid/debug/DebugLogger.kt`

**Modified files (core, ~6):**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` -- swap `FileLogger.init` for `AppLog.init`.
- `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt` -- full call-site migration (~75 sites).
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` -- session markers, stats, broadcast.
- `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsViewModel.kt` -- level state, migration.
- `android/app/src/main/java/com/sendspindroid/ui/settings/SettingsScreen.kt` -- segmented button row, clear-logs button.
- `android/app/src/main/java/com/sendspindroid/SettingsActivity.kt` -- intent plumbing unchanged, renames only.

**Modified resources:**
- `android/app/src/main/res/xml/file_provider_paths.xml` -- expose `cache/logs/`.
- `android/app/src/main/res/values/strings.xml` -- add/remove strings per Settings UI section.

**New test files (3):**
- `android/app/src/test/java/com/sendspindroid/logging/LogFileWriterTest.kt`
- `android/app/src/test/java/com/sendspindroid/logging/AppLogTest.kt`
- `android/app/src/androidTest/java/com/sendspindroid/logging/LogcatBridgeInstrumentedTest.kt`
