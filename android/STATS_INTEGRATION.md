# Stats for Nerds - Integration Guide

This document explains how to integrate and use the "Stats for Nerds" feature in SendSpin Player.

## Overview

The Stats for Nerds feature provides real-time audio synchronization diagnostics in a Material 3 bottom sheet dialog. It displays:

- **Sync Status**: Playback state, sync error measurements
- **Buffer**: Chunk statistics, queue depth, gap/overlap handling
- **Sync Correction**: Sample insert/drop statistics
- **Clock Sync**: Kalman filter state, DAC calibration

## Files Created

### 1. Layout
- **Z:\CodeProjects\SpinDroid\android\app\src\main\res\layout\fragment_stats.xml**
  - Material 3 bottom sheet layout with dark technical aesthetic
  - Scrollable card-based sections
  - Monospace font for values
  - Color-coded status indicators

### 2. Fragment/Dialog
- **Z:\CodeProjects\SpinDroid\android\app\src\main\java\com\sendspindroid\StatsBottomSheet.kt**
  - BottomSheetDialogFragment implementation
  - Updates at 10 Hz (100ms intervals)
  - Fetches stats via MediaController custom command
  - Color-codes values based on thresholds

### 3. Data Model
- **Z:\CodeProjects\SpinDroid\android\app\src\main\java\com\sendspindroid\model\SyncStats.kt**
  - Data class for stats aggregation
  - Bundle serialization for MediaSession extras
  - Mirrors SyncAudioPlayer.SyncStats structure

### 4. Resources
- **strings.xml**: All stats labels and descriptions
- **colors.xml**: Dark theme colors matching Windows WPF reference
  - Background: #1a1a2e (dark navy)
  - Cards: #2d2d44
  - Values: Green (#4ade80), Yellow (#fbbf24), Red (#f87171)
  - Section headers: Purple (#a78bfa)
- **drawable/ic_info.xml**: Info icon for menu
- **drawable/ic_close.xml**: Close icon for bottom sheet

### 5. Menu
- **menu/menu_now_playing.xml**: Added "Stats for Nerds" menu item

## Integration Steps

### 1. PlaybackService Changes

Added `COMMAND_GET_STATS` command and `getStats()` method:

```kotlin
// In PlaybackService companion object
const val COMMAND_GET_STATS = "com.sendspindroid.GET_STATS"

// In onCustomCommand()
COMMAND_GET_STATS -> {
    val statsBundle = getStats()
    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, statsBundle))
}

// New method to collect stats
private fun getStats(): Bundle {
    // Aggregates stats from SyncAudioPlayer and SendSpinClient
    // Returns Bundle for MediaController
}
```

### 2. MainActivity Changes

Added menu item handler:

```kotlin
override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_stats -> {
            StatsBottomSheet().show(supportFragmentManager, "stats")
            true
        }
        // ... existing handlers
    }
}
```

### 3. SendspinTimeFilter Changes

Added `measurementCountValue` property to expose measurement count:

```kotlin
val measurementCountValue: Int
    get() = measurementCount
```

## Usage

### From MainActivity

When connected to a server, the "Stats for Nerds" menu item appears in the overflow menu (three dots). Tapping it opens the bottom sheet with real-time stats.

```kotlin
// Programmatically show stats (if needed elsewhere)
StatsBottomSheet().show(supportFragmentManager, "stats")
```

### Stats Update Flow

```
StatsBottomSheet
    │
    ├─► MediaController.sendCustomCommand(GET_STATS)
    │
    └─► PlaybackService.getStats()
            │
            ├─► SyncAudioPlayer.getStats()
            └─► SendSpinClient.getTimeFilter()
                    │
                    └─► Bundle (all stats)
                            │
                            └─► StatsBottomSheet.updateStatsUI()
```

## Color Coding Thresholds

### Sync Error
- **Green**: < 2ms (good sync)
- **Yellow**: 2-10ms (acceptable)
- **Red**: > 10ms (poor sync)

### Clock Error
- **Green**: < 1ms (excellent)
- **Yellow**: 1-5ms (acceptable)
- **Red**: > 5ms (needs improvement)

### Buffer Level
- **Red**: < 50ms (low, risk of underrun)
- **Yellow**: 50-200ms (medium)
- **Green**: > 200ms (healthy)

### Playback State
- **Green**: PLAYING (normal operation)
- **Yellow**: WAITING_FOR_START, INITIALIZING
- **Red**: REANCHORING (sync recovery)

## Accessibility

The stats dialog is fully accessible:
- All values have proper content descriptions
- Screen readers announce current state
- Bottom sheet can be dismissed with back button or close button
- High contrast colors for readability

## Performance Considerations

- Updates at 10 Hz (100ms) provide smooth real-time feedback
- Handler-based updates avoid excessive CPU usage
- MediaController commands are asynchronous
- Bottom sheet cleanup on dismiss prevents memory leaks

## Future Enhancements

Potential improvements for v2:

1. **Export Stats**: Add button to export stats as CSV/JSON
2. **Graphing**: Real-time line charts for sync error over time
3. **Notifications**: Alert when sync quality degrades
4. **Advanced Mode**: Additional technical metrics (drift, variance, etc.)
5. **Comparison Mode**: Compare stats across multiple devices
6. **Performance Profile**: CPU/memory usage statistics

## Troubleshooting

### Stats Not Updating
- Check that PlaybackService is connected
- Verify SyncAudioPlayer is initialized
- Check LogCat for "StatsBottomSheet" tag errors

### Incorrect Values
- Ensure clock is synchronized (Clock Ready = Yes)
- Wait for DAC calibrations to populate (takes ~5 seconds)
- Check that audio is actually playing

### Bottom Sheet Not Showing
- Verify menu item is visible (only when connected)
- Check FragmentManager state
- Look for inflation errors in LogCat

## Design Notes

This implementation follows Material 3 design guidelines while maintaining a "technical" aesthetic inspired by the Windows WPF reference design:

- **Dark theme**: Reduces eye strain during debugging
- **Monospace font**: Easier to read changing numerical values
- **Card layout**: Groups related stats logically
- **Purple headers**: Maintains brand consistency with Material 3 primary color
- **Color coding**: Instant visual feedback on system health

## Testing Checklist

- [ ] Stats bottom sheet opens from menu
- [ ] All sections display with correct labels
- [ ] Values update every 100ms
- [ ] Colors change based on thresholds
- [ ] Close button dismisses sheet
- [ ] Back button dismisses sheet
- [ ] No memory leaks after multiple open/close cycles
- [ ] Stats accurate during playback
- [ ] Stats show zero/default when disconnected
- [ ] Accessible with TalkBack enabled
- [ ] Works in landscape orientation
- [ ] Works on tablets (larger screens)

## API Reference

### StatsBottomSheet

**Constructor**: `StatsBottomSheet()`

**Methods**:
- None (use standard `show()` from BottomSheetDialogFragment)

**Lifecycle**:
- `onCreateView()`: Inflates layout
- `onViewCreated()`: Sets up UI and MediaController
- `startUpdates()`: Begins 10 Hz polling
- `onDestroyView()`: Cleans up Handler and MediaController

### PlaybackService.getStats()

**Returns**: `Bundle` with the following keys:

#### Playback State
- `playback_state` (String): INITIALIZING, WAITING_FOR_START, PLAYING, REANCHORING
- `is_playing` (Boolean): Whether audio is currently playing

#### Sync Status
- `sync_error_us` (Long): Smoothed sync error in microseconds
- `true_sync_error_us` (Long): True sync error from DAC timestamps

#### Buffer
- `queued_samples` (Long): Samples in queue
- `chunks_received` (Long): Total chunks received
- `chunks_played` (Long): Total chunks played
- `chunks_dropped` (Long): Total chunks dropped
- `gaps_filled` (Long): Number of gaps filled with silence
- `gap_silence_ms` (Long): Total milliseconds of silence inserted
- `overlaps_trimmed` (Long): Number of overlaps trimmed
- `overlap_trimmed_ms` (Long): Total milliseconds of audio trimmed

#### Sync Correction
- `insert_every_n_frames` (Int): Insert frame every N frames (0 = none)
- `drop_every_n_frames` (Int): Drop frame every N frames (0 = none)
- `frames_inserted` (Long): Total frames inserted
- `frames_dropped` (Long): Total frames dropped
- `sync_corrections` (Long): Total sync correction events
- `correction_error_us` (Long): Smoothed error used for correction scheduling

#### Clock Sync
- `clock_ready` (Boolean): Whether Kalman filter is ready
- `clock_offset_us` (Long): Clock offset in microseconds
- `clock_error_us` (Long): Clock uncertainty in microseconds
- `measurement_count` (Int): Number of NTP measurements collected

#### DAC Calibration
- `dac_calibration_count` (Int): Number of calibration points
- `total_frames_written` (Long): Total frames written to AudioTrack
- `last_known_playback_position_us` (Long): Server time currently at DAC
- `server_timeline_cursor_us` (Long): Server time fed up to

## License

This feature is part of SendSpin Player and follows the same license as the main project.
