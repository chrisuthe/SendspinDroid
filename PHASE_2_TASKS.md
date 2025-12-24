# Phase 2: ExoPlayer Bridge - Detailed Task Breakdown

This breaks down Phase 2 (ExoPlayer bridge implementation) into smaller, more manageable tasks that can be worked on incrementally and in parallel where possible.

**Original Phase 2 Tasks:**
- Task 2.1: Implement GoPlayerDataSource (2-3 hours, complexity 8/10)
- Task 2.2: Implement GoPlayerMediaSource (2-3 hours, complexity 8/10)

**Problem:** These tasks are too complex and monolithic. Breaking them down further.

---

## Revised Phase 2 Task Breakdown

### Task 2.1: Create GoPlayerDataSource Skeleton
**Agent Type:** General-purpose
**Time Estimate:** 30 minutes
**Complexity:** Low (3/10)
**Breaks App:** ❌ No - New class, not used yet
**Depends On:** Task 1.1 (Media3 dependencies), Task 1.3 (research completed)

**Description:**
Create the basic structure of GoPlayerDataSource extending BaseDataSource with method stubs.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSource.kt`

**Implementation:**
```kotlin
package com.sendspindroid.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import player.Player_

/**
 * Custom DataSource that bridges Go player's audio output to ExoPlayer.
 * Reads PCM audio data from Go player's readAudioData() method.
 */
class GoPlayerDataSource(
    private val goPlayer: Player_
) : BaseDataSource(/* isNetwork = */ true) {

    private val buffer = ByteArray(8192)
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        // TODO: Implement in Task 2.2
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        // TODO: Implement in Task 2.3
        return C.RESULT_END_OF_INPUT
    }

    override fun close() {
        // TODO: Implement in Task 2.4
    }

    override fun getUri(): Uri? {
        // TODO: Implement in Task 2.4
        return null
    }
}
```

**Deliverables:**
- [ ] GoPlayerDataSource class created with all required method stubs
- [ ] Proper package declaration
- [ ] Constructor accepts Player_ parameter
- [ ] Extends BaseDataSource correctly
- [ ] Code compiles successfully
- [ ] No implementation yet (stubs only)

**Success Criteria:**
- File compiles without errors
- All abstract methods stubbed out
- Ready for implementation in next tasks

---

### Task 2.2: Implement GoPlayerDataSource.open()
**Agent Type:** General-purpose
**Time Estimate:** 45 minutes
**Complexity:** Medium (5/10)
**Breaks App:** ❌ No - Still not integrated
**Depends On:** Task 2.1

**Description:**
Implement the open() method which is called when ExoPlayer starts reading from the DataSource.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSource.kt`

**Implementation Requirements:**
- Mark DataSource as opened
- Call transferStarted(dataSpec) to notify listeners
- Return C.LENGTH_UNSET (unknown stream length for live audio)
- Handle potential errors (throw IOException if needed)

**Code to Add:**
```kotlin
override fun open(dataSpec: DataSpec): Long {
    if (opened) {
        throw IOException("DataSource already opened")
    }

    Log.d(TAG, "Opening GoPlayerDataSource for: ${dataSpec.uri}")

    // Notify that data transfer is starting
    transferStarted(dataSpec)

    opened = true
    bytesRemaining = C.LENGTH_UNSET.toLong()

    // Return unknown length since this is a live stream
    return C.LENGTH_UNSET.toLong()
}
```

**Deliverables:**
- [ ] open() method fully implemented
- [ ] transferStarted() called correctly
- [ ] Error handling for double-open
- [ ] Logging added for debugging
- [ ] Code compiles and passes basic logic review

**Success Criteria:**
- Method follows ExoPlayer DataSource contract
- Proper state management (opened flag)
- Ready for read() implementation

---

### Task 2.3: Implement GoPlayerDataSource.read()
**Agent Type:** General-purpose
**Time Estimate:** 1.5 hours
**Complexity:** High (8/10)
**Breaks App:** ❌ No - Still not integrated
**Depends On:** Task 2.2

**Description:**
Implement the critical read() method that transfers audio data from Go player to ExoPlayer.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSource.kt`

**Implementation Requirements:**
- Handle zero-length reads
- Call goPlayer.readAudioData() to get audio bytes
- Copy data to target buffer at correct offset
- Report bytes transferred
- Handle end-of-stream
- Handle errors gracefully

**Code to Add:**
```kotlin
companion object {
    private const val TAG = "GoPlayerDataSource"
}

override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) {
        return 0
    }

    if (!opened) {
        throw IOException("DataSource not opened")
    }

    try {
        // Read from Go player into our buffer
        val bytesRead = goPlayer.readAudioData(buffer)

        if (bytesRead <= 0) {
            // End of stream or no data available
            Log.d(TAG, "End of stream or no data: bytesRead=$bytesRead")
            return C.RESULT_END_OF_INPUT
        }

        // Copy to ExoPlayer's target buffer
        val bytesToCopy = minOf(bytesRead, length)
        System.arraycopy(buffer, 0, target, offset, bytesToCopy)

        // Notify how many bytes were transferred
        bytesTransferred(bytesToCopy)

        return bytesToCopy

    } catch (e: Exception) {
        Log.e(TAG, "Error reading audio data from Go player", e)
        throw IOException("Failed to read from Go player", e)
    }
}
```

**Deliverables:**
- [ ] read() method fully implemented
- [ ] Proper buffer copying with offset handling
- [ ] bytesTransferred() called correctly
- [ ] End-of-stream detection
- [ ] Error handling and logging
- [ ] Thread-safety considerations documented

**Success Criteria:**
- Correctly reads from Go player
- Proper buffer management (no overruns)
- Error handling in place
- Logging for debugging

---

### Task 2.4: Implement GoPlayerDataSource.close() and getUri()
**Agent Type:** General-purpose
**Time Estimate:** 30 minutes
**Complexity:** Low (3/10)
**Breaks App:** ❌ No - Still not integrated
**Depends On:** Task 2.3

**Description:**
Implement cleanup and URI methods to complete GoPlayerDataSource.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSource.kt`

**Implementation Requirements:**

**close() method:**
- Mark DataSource as closed
- Call transferEnded() to notify listeners
- Do NOT cleanup Go player (managed by service)

**getUri() method:**
- Return custom URI for this stream
- Use "sendspin://stream" scheme

**Code to Add:**
```kotlin
override fun close() {
    if (opened) {
        Log.d(TAG, "Closing GoPlayerDataSource")
        opened = false
        transferEnded()
    }
}

override fun getUri(): Uri? {
    return if (opened) {
        Uri.parse("sendspin://stream")
    } else {
        null
    }
}
```

**Deliverables:**
- [ ] close() method implemented
- [ ] getUri() method implemented
- [ ] transferEnded() called correctly
- [ ] State management (opened flag)
- [ ] GoPlayerDataSource is complete and ready to test

**Success Criteria:**
- Proper cleanup on close
- URI available when opened
- No Go player lifecycle interference
- DataSource fully functional

---

### Task 2.5: Create GoPlayerDataSourceFactory
**Agent Type:** General-purpose
**Time Estimate:** 20 minutes
**Complexity:** Low (2/10)
**Breaks App:** ❌ No - Not integrated yet
**Depends On:** Task 2.4 (GoPlayerDataSource complete)

**Description:**
Create factory class for GoPlayerDataSource as required by ExoPlayer.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSourceFactory.kt`

**Implementation:**
```kotlin
package com.sendspindroid.playback

import androidx.media3.datasource.DataSource
import player.Player_

/**
 * Factory for creating GoPlayerDataSource instances.
 * Required by ExoPlayer's MediaSource API.
 */
class GoPlayerDataSourceFactory(
    private val goPlayer: Player_
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return GoPlayerDataSource(goPlayer)
    }
}
```

**Deliverables:**
- [ ] GoPlayerDataSourceFactory class created
- [ ] Implements DataSource.Factory interface
- [ ] createDataSource() returns GoPlayerDataSource
- [ ] Code compiles successfully

**Success Criteria:**
- Follows ExoPlayer factory pattern
- Simple and correct implementation
- Ready for MediaSource integration

---

### Task 2.6: Create GoPlayerMediaSource
**Agent Type:** General-purpose
**Time Estimate:** 2 hours
**Complexity:** High (8/10)
**Breaks App:** ❌ No - Not integrated yet
**Depends On:** Task 2.5 (Factory complete)

**Description:**
Create custom MediaSource that uses GoPlayerDataSource to provide audio to ExoPlayer. This is complex because it involves implementing ExoPlayer's MediaSource/MediaPeriod API.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerMediaSource.kt`

**Implementation Requirements:**
- Extend BaseMediaSource (or implement MediaSource)
- Define audio format matching Go player output
- Implement prepareSourceInternal()
- Implement createPeriod()
- Implement releasePeriod()
- Create MediaPeriod that wraps GoPlayerDataSource

**Simplified Implementation Strategy:**
Use `ProgressiveMediaSource` as a base and wrap our custom DataSource:

```kotlin
package com.sendspindroid.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import player.Player_

/**
 * MediaSource that provides audio from Go player via custom DataSource.
 */
object GoPlayerMediaSourceFactory {

    fun create(goPlayer: Player_): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri("sendspin://stream")
            .build()

        val dataSourceFactory = GoPlayerDataSourceFactory(goPlayer)

        // Use ProgressiveMediaSource with our custom DataSource
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
}
```

**Alternative Approach (if ProgressiveMediaSource doesn't work):**
Implement full custom MediaSource with MediaPeriod - this is significantly more complex and would require breaking this into 2-3 additional sub-tasks.

**Deliverables:**
- [ ] GoPlayerMediaSource created (or factory method)
- [ ] Audio format configured correctly (PCM, 16-bit, stereo, 48kHz)
- [ ] MediaItem with custom URI
- [ ] DataSourceFactory integration
- [ ] Code compiles successfully
- [ ] Ready for PlaybackService integration

**Success Criteria:**
- MediaSource follows ExoPlayer contract
- Audio format matches Go player output
- Can be used by ExoPlayer
- No integration yet (just infrastructure)

---

## Phase 2 Execution Plan

### Sequential Execution Required
Unlike Phase 1, Phase 2 tasks must run sequentially because each builds on the previous:

1. **Task 2.1** → Create skeleton (30 min)
2. **Task 2.2** → Implement open() (45 min)
3. **Task 2.3** → Implement read() (1.5 hours)
4. **Task 2.4** → Implement close() and getUri() (30 min)
5. **Task 2.5** → Create factory (20 min)
6. **Task 2.6** → Create MediaSource (2 hours)

**Total Time:** ~5.5 hours

### Parallel Opportunities
Tasks 2.5 and 2.6 could potentially run in parallel if 2.6 uses the factory pattern approach, but there's minimal time savings.

---

## Risk Analysis

**Highest Risk Task:** 2.3 (read() implementation)
- Complex buffer management
- Thread safety concerns with Go player
- Blocking vs non-blocking reads
- Error handling for Go player failures

**Mitigation:**
- Task 1.3 research should inform implementation
- Add extensive logging for debugging
- Test with small buffer sizes first
- Consider adding timeout handling

**Fallback Plan:**
If ExoPlayer bridge proves too complex:
- Abandon Tasks 2.1-2.6
- Create simpler MediaSessionService with AudioTrack
- Manually manage notifications (more code, but simpler)
- Keep existing MainActivity audio code

---

## Testing Strategy (After Phase 2 Complete)

**Unit Testing** (Optional but Recommended):
- Test GoPlayerDataSource with mock Player_
- Verify buffer handling with various sizes
- Test error conditions
- Verify state transitions (open → read → close)

**Integration Testing** (Phase 3):
- Wire GoPlayerMediaSource into ExoPlayer
- Test with real Go player connection
- Verify audio output quality
- Check for buffer underruns/overruns

---

## Success Criteria for Phase 2

When Phase 2 is complete, we should have:
- ✅ GoPlayerDataSource fully implemented
- ✅ GoPlayerDataSourceFactory created
- ✅ GoPlayerMediaSource created
- ✅ All code compiles successfully
- ✅ No changes to existing app functionality
- ✅ Ready to integrate into PlaybackService (Phase 3)

**Still NOT working:**
- ❌ No playback through ExoPlayer yet (needs Phase 3)
- ❌ No service integration yet (needs Phase 3)
- ❌ MainActivity still uses old AudioTrack (until Phase 4)

This ensures the app keeps working while we build the new infrastructure.
