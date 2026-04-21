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
