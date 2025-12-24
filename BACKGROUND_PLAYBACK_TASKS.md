# Background Playback - Incremental Implementation Tasks

This document breaks down the background playback implementation into smaller, parallel tasks that can be assigned to background agents without breaking the existing application functionality between tasks.

**Strategy:** Build the new Media3/ExoPlayer infrastructure in parallel with the existing AudioTrack implementation, then switch over in the final integration task.

---

## Task Group 1: Foundation (Can Run in Parallel) ⚡

### Task 1.1: Add Media3 Dependencies & Permissions
**Agent Type:** General-purpose
**Time Estimate:** 30 minutes
**Complexity:** Low (2/10)
**Breaks App:** ❌ No - Just adds dependencies

**Description:**
Add Media3 libraries and required permissions without touching existing code.

**Files to Modify:**
- `android/app/build.gradle.kts` - Add Media3 dependencies
- `android/app/src/main/AndroidManifest.xml` - Add foreground service permissions

**Deliverables:**
- [ ] Media3 dependencies added (media3-session, media3-exoplayer, media3-common)
- [ ] FOREGROUND_SERVICE permission added
- [ ] FOREGROUND_SERVICE_MEDIA_PLAYBACK permission added
- [ ] POST_NOTIFICATIONS permission added
- [ ] WAKE_LOCK permission added
- [ ] App builds successfully
- [ ] No changes to existing functionality

**Success Criteria:**
- Gradle sync succeeds
- App runs normally with no behavior changes
- No build errors or warnings

---

### Task 1.2: Create Notification Channel Infrastructure
**Agent Type:** General-purpose
**Time Estimate:** 45 minutes
**Complexity:** Low (2/10)
**Breaks App:** ❌ No - Creates new utility, doesn't use it yet

**Description:**
Create notification channel setup code in a new utility class. This doesn't change any existing behavior.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/NotificationHelper.kt`

**Deliverables:**
- [ ] NotificationHelper utility class created
- [ ] createNotificationChannel() method implemented
- [ ] Proper notification channel configuration (IMPORTANCE_LOW, no badge)
- [ ] Code compiles but is not yet used

**Code Template:**
```kotlin
object NotificationHelper {
    const val CHANNEL_ID = "playback_channel"
    const val NOTIFICATION_ID = 101

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls for audio playback"
            setShowBadge(false)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
```

**Success Criteria:**
- Code compiles successfully
- No runtime changes (not called yet)
- Utility is ready for future use

---

### Task 1.3: Research & Document ExoPlayer DataSource API
**Agent Type:** Explore
**Time Estimate:** 1 hour
**Complexity:** Medium (5/10)
**Breaks App:** ❌ No - Documentation only

**Description:**
Research how to implement custom ExoPlayer DataSource for bridging Go player audio. Create detailed documentation with code examples.

**Deliverables:**
- [ ] Document ExoPlayer DataSource.Factory requirements
- [ ] Document BaseDataSource abstract methods
- [ ] Identify thread safety requirements for readAudioData()
- [ ] Document buffer management between Go and ExoPlayer
- [ ] Create pseudocode for GoPlayerDataSource implementation
- [ ] Identify potential issues with blocking reads

**Output File:**
- `EXOPLAYER_DATASOURCE_RESEARCH.md`

**Success Criteria:**
- Clear understanding of DataSource API
- Thread safety concerns documented
- Implementation approach validated
- No code changes yet

---

## Task Group 2: ExoPlayer Bridge (Can Run in Parallel) ⚡

### Task 2.1: Implement GoPlayerDataSource
**Agent Type:** General-purpose
**Time Estimate:** 2-3 hours
**Complexity:** High (8/10)
**Breaks App:** ❌ No - New class, not used yet
**Depends On:** Task 1.1 (Media3 dependencies), Task 1.3 (research)

**Description:**
Implement custom DataSource that bridges Go player's readAudioData() to ExoPlayer's data model.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSource.kt`
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerDataSourceFactory.kt`

**Key Requirements:**
- Extend BaseDataSource with isNetwork=true
- Implement open(), read(), close() methods
- Handle blocking reads from Go player
- Proper error handling for end-of-stream
- Thread-safe buffer management
- Report bytes transferred for buffering stats

**Deliverables:**
- [ ] GoPlayerDataSource class implemented
- [ ] GoPlayerDataSourceFactory class implemented
- [ ] Proper error handling for readAudioData() failures
- [ ] Buffer size optimization (8192 bytes initial)
- [ ] Unit tests for DataSource (optional but recommended)
- [ ] Code compiles successfully
- [ ] Not integrated yet - no app behavior changes

**Success Criteria:**
- Code compiles without errors
- DataSource correctly implements BaseDataSource contract
- Thread safety verified (no shared mutable state)
- Ready for integration testing

---

### Task 2.2: Implement GoPlayerMediaSource
**Agent Type:** General-purpose
**Time Estimate:** 2-3 hours
**Complexity:** High (8/10)
**Breaks App:** ❌ No - New class, not used yet
**Depends On:** Task 2.1 (GoPlayerDataSource)

**Description:**
Implement custom MediaSource that uses GoPlayerDataSource to provide audio to ExoPlayer.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/GoPlayerMediaSource.kt`

**Key Requirements:**
- Extend BaseMediaSource or implement MediaSource
- Use GoPlayerDataSourceFactory
- Define audio format (PCM 16-bit, stereo, 48kHz)
- Handle MediaPeriod creation
- Support ExoPlayer's buffering model
- Proper cleanup on release

**Deliverables:**
- [ ] GoPlayerMediaSource class implemented
- [ ] Audio format correctly configured for Go player output
- [ ] MediaPeriod implementation (simplified for streaming)
- [ ] Timeline management (EMPTY timeline for live stream)
- [ ] Code compiles successfully
- [ ] Not integrated yet - no app behavior changes

**Success Criteria:**
- Code compiles without errors
- MediaSource correctly implements contract
- Audio format matches Go player output
- Ready for integration with ExoPlayer

---

## Task Group 3: Service Infrastructure (Sequential)

### Task 3.1: Create PlaybackService (Stub Version)
**Agent Type:** General-purpose
**Time Estimate:** 1.5 hours
**Complexity:** Medium (6/10)
**Breaks App:** ❌ No - Service exists but not used yet
**Depends On:** Task 1.1, Task 1.2, Task 2.2

**Description:**
Create PlaybackService extending MediaSessionService with basic lifecycle, but don't connect it to MainActivity yet.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Files to Modify:**
- `android/app/src/main/AndroidManifest.xml` - Register service (but not used yet)

**Key Requirements:**
- Extend MediaSessionService
- Initialize MediaSession with ExoPlayer
- Create notification channel in onCreate
- Implement basic MediaSession.Callback (play/pause/stop stubs)
- Register service in manifest with proper intent-filter
- Don't initialize Go player yet (will cause conflicts)

**Deliverables:**
- [ ] PlaybackService class created
- [ ] MediaSession initialized with ExoPlayer
- [ ] GoPlayerMediaSource wired to ExoPlayer (using null player for now)
- [ ] Basic callback stubs implemented
- [ ] Service registered in manifest
- [ ] App still uses MainActivity's audio playback (no changes)
- [ ] Service can be started but does nothing yet

**Success Criteria:**
- App builds successfully
- Existing playback still works normally
- Service can be started manually for testing
- No crashes or side effects

---

### Task 3.2: Add Go Player to PlaybackService
**Agent Type:** General-purpose
**Time Estimate:** 2 hours
**Complexity:** Medium-High (7/10)
**Breaks App:** ⚠️ Potential - Service now manages Go player
**Depends On:** Task 3.1

**Description:**
Move Go player initialization to PlaybackService and wire up callbacks. This creates duplicate player instances temporarily.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Key Requirements:**
- Initialize Go player in service onCreate
- Implement PlayerCallback inner class
- Wire Go player to GoPlayerMediaSource
- Handle metadata updates via MediaSession
- Implement play/pause/stop commands to Go player
- Proper cleanup in onDestroy

**Deliverables:**
- [ ] Go player initialized in service
- [ ] PlayerCallback implemented
- [ ] Metadata flows from Go callbacks to MediaSession
- [ ] Play/pause/stop controls work
- [ ] Proper cleanup on service destroy
- [ ] MainActivity still has its own player (not connected yet)

**Testing Strategy:**
- Service can be started independently
- Connect to a server through service (via manual test)
- Verify playback works through service
- Verify MainActivity playback still works
- Both can exist simultaneously without crashes

**Success Criteria:**
- Service can play audio independently
- MainActivity audio still works
- No interference between the two
- Ready for MainActivity integration

---

## Task Group 4: MainActivity Integration (Final Switchover)

### Task 4.1: Add MediaController to MainActivity
**Agent Type:** General-purpose
**Time Estimate:** 1.5 hours
**Complexity:** Medium (6/10)
**Breaks App:** ⚠️ Partial - Dual playback paths exist
**Depends On:** Task 3.2

**Description:**
Add MediaController connection to MainActivity alongside existing playback code. Create service connection but don't remove old code yet.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

**Key Requirements:**
- Add MediaController field
- Create SessionToken for PlaybackService
- Build MediaController asynchronously
- Keep existing audioPlayer and audioTrack code intact
- Add flag to switch between old/new playback

**Deliverables:**
- [ ] MediaController connection added
- [ ] SessionToken created
- [ ] Service binding implemented
- [ ] Debug toggle to switch playback modes
- [ ] Both old and new code paths coexist
- [ ] Can test service playback from MainActivity

**Code Strategy:**
```kotlin
private var useServicePlayback = false // Toggle for testing
private var mediaController: MediaController? = null

// Keep existing:
// private var audioPlayer: Player_? = null
// private var audioTrack: AudioTrack? = null
```

**Success Criteria:**
- Can toggle between old and new playback
- Both modes work independently
- No crashes when switching
- Ready for final migration

---

### Task 4.2: Migrate Server Connection to Service
**Agent Type:** General-purpose
**Time Estimate:** 1.5 hours
**Complexity:** Medium (6/10)
**Breaks App:** ⚠️ Yes - This is the switchover
**Depends On:** Task 4.1

**Description:**
Move server connection logic to use PlaybackService. Remove old AudioTrack-based playback code.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt`
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Key Requirements:**
- Server selection starts PlaybackService
- Pass server address via Intent extras or MediaController
- Remove old audioPlayer initialization
- Remove old AudioTrack code
- Remove old audioPlaybackJob coroutine
- Update UI controls to use MediaController
- Migrate volume slider to MediaController

**Deliverables:**
- [ ] onServerSelected() starts service with server address
- [ ] Service handles connection internally
- [ ] Old playback code removed
- [ ] UI controls use MediaController exclusively
- [ ] Play/pause/stop buttons work
- [ ] Volume slider controls MediaController volume
- [ ] Status updates still work (need service → activity communication)

**Critical:**
- Keep metadata callback working
- Ensure connection status updates UI
- Handle disconnection properly
- Test background playback thoroughly

**Success Criteria:**
- Background playback works (screen lock, app switch)
- Lock screen controls appear
- Notification shows with metadata
- All existing functionality preserved
- Old code fully removed

---

### Task 4.3: Implement Service → Activity Communication
**Agent Type:** General-purpose
**Time Estimate:** 1 hour
**Complexity:** Medium (5/10)
**Breaks App:** ❌ No - Enhancement only
**Depends On:** Task 4.2

**Description:**
Set up proper communication from PlaybackService to MainActivity for status updates, metadata, etc.

**Approach Options:**
1. **MediaController.Listener** - Monitor playback state changes
2. **Broadcast Intents** - Service sends broadcasts, Activity receives
3. **SharedFlow/StateFlow** - Shared state repository pattern

**Recommended:** MediaController.Listener (built-in, proper architecture)

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

**Deliverables:**
- [ ] MediaController.Listener registered
- [ ] UI updates on playback state changes
- [ ] Metadata updates reflected in UI
- [ ] Connection status updates
- [ ] Error states propagated to UI
- [ ] Smooth transition between Activity and Service

**Success Criteria:**
- UI stays in sync with playback state
- Metadata updates in real-time
- Status messages work correctly
- No UI glitches or delays

---

## Task Group 5: Polish & Testing

### Task 5.1: Add Audio Focus Handling
**Agent Type:** General-purpose
**Time Estimate:** 1 hour
**Complexity:** Low (3/10)
**Breaks App:** ❌ No - Enhancement only
**Depends On:** Task 4.2

**Description:**
Configure ExoPlayer to handle audio focus properly for phone calls, notifications, etc.

**Files to Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Deliverables:**
- [ ] AudioAttributes configured
- [ ] Audio focus handling enabled
- [ ] Phone calls pause playback
- [ ] Navigation voice pauses/ducks playback
- [ ] Playback resumes after interruption

**Success Criteria:**
- Phone calls pause music
- Music resumes after call ends
- Navigation audio ducks music volume
- Multiple audio sources handled correctly

---

### Task 5.2: Handle Configuration Changes
**Agent Type:** General-purpose
**Time Estimate:** 45 minutes
**Complexity:** Low (3/10)
**Breaks App:** ❌ No - Enhancement only
**Depends On:** Task 4.2

**Description:**
Verify and test that rotation/configuration changes don't break playback.

**Files to Test:**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

**Deliverables:**
- [ ] Rotation doesn't stop playback
- [ ] Server list persists (or re-discovers)
- [ ] Connection state survives rotation
- [ ] UI reconnects to service correctly
- [ ] MediaController rebinds properly

**Success Criteria:**
- Seamless rotation while playing
- No playback interruptions
- UI state preserved or restored
- No memory leaks

---

### Task 5.3: Comprehensive Testing
**Agent Type:** General-purpose
**Time Estimate:** 2-3 hours
**Complexity:** Medium (5/10)
**Breaks App:** ❌ No - Testing only
**Depends On:** All previous tasks

**Description:**
Thorough testing of background playback functionality across all scenarios.

**Test Checklist:**

**Basic Functionality:**
- [ ] Background playback (home button)
- [ ] Screen lock playback
- [ ] Notification appears
- [ ] Lock screen controls
- [ ] Metadata displays correctly
- [ ] Play/pause from notification
- [ ] Stop from notification

**Edge Cases:**
- [ ] Phone call interruption
- [ ] Bluetooth headset controls
- [ ] App switch doesn't stop music
- [ ] Rotation during playback
- [ ] Network disconnection
- [ ] Server connection failure
- [ ] Service restart (low memory)

**Battery/Performance:**
- [ ] Doze mode compatibility
- [ ] Battery usage reasonable
- [ ] No wakelocks when paused
- [ ] CPU usage normal

**Deliverables:**
- [ ] Test report document
- [ ] List of bugs found (if any)
- [ ] Performance metrics
- [ ] Battery usage analysis

---

## Task Assignment Strategy

### Parallel Execution (Phase 1)
Launch these agents simultaneously:
1. **Task 1.1** - Dependencies & permissions
2. **Task 1.2** - Notification helper
3. **Task 1.3** - ExoPlayer research

**Wait for Phase 1 completion, then launch Phase 2 in parallel:**

### Parallel Execution (Phase 2)
4. **Task 2.1** - GoPlayerDataSource
5. **Task 2.2** - GoPlayerMediaSource (starts after 2.1)

**Wait for Phase 2 completion, then sequential execution:**

### Sequential Execution (Phase 3)
6. **Task 3.1** - PlaybackService stub
7. **Task 3.2** - Add Go player to service
8. **Task 4.1** - MediaController in MainActivity
9. **Task 4.2** - Migrate to service (THE BIG SWITCHOVER)
10. **Task 4.3** - Service → Activity communication

### Parallel Execution (Phase 4 - Polish)
11. **Task 5.1** - Audio focus
12. **Task 5.2** - Configuration changes

### Final Task
13. **Task 5.3** - Comprehensive testing

---

## Risk Mitigation

**Biggest Risk:** ExoPlayer bridge complexity (Tasks 2.1, 2.2)

**Mitigation:**
- Complete research first (Task 1.3)
- Build DataSource independently
- Test with dummy audio before Go integration
- Have fallback plan ready (AudioTrack service wrapper)

**Fallback Plan:**
If ExoPlayer bridge proves too complex:
1. Pause Tasks 2.1, 2.2
2. Create simpler MediaSessionService with AudioTrack
3. Copy existing AudioTrack code into service
4. Manual notification management (more code, but simpler)

---

## Success Metrics

**Definition of Done:**
- ✅ Lock screen playback works
- ✅ Notification shows with controls
- ✅ Metadata updates correctly
- ✅ Phone calls pause automatically
- ✅ App switch preserves playback
- ✅ Rotation doesn't interrupt
- ✅ No memory leaks
- ✅ Battery usage acceptable
- ✅ All old playback code removed
- ✅ No regressions in existing features

**Timeline Estimate:**
- Phase 1 (Parallel): 2 hours
- Phase 2 (Parallel): 4-5 hours
- Phase 3 (Sequential): 6-7 hours
- Phase 4 (Polish): 2-3 hours
- Phase 5 (Testing): 2-3 hours

**Total: 16-20 hours** (matches original estimate)

---

## Command to Launch First Phase

```
# Launch all Phase 1 tasks in parallel
1. Assign general-purpose agent: Task 1.1
2. Assign general-purpose agent: Task 1.2
3. Assign explore agent: Task 1.3
```

Ready to begin?
