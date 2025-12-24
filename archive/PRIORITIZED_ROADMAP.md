# SendSpinDroid - Prioritized Development Roadmap

**Last Updated:** December 23, 2025
**Current Version:** v0.0.3
**Target:** v1.0 Production Release

---

## ✅ Recently Completed (v0.0.3)

- ✅ Fix memory leak - AudioTrack not stopped on disconnect
- ✅ Fix race condition - Multiple audio streams
- ✅ Update to API 35 for Play Store compliance
- ✅ Fix AndroidManifest.xml syntax errors

---

## 🎯 Priority 1: CRITICAL FOR v1.0 (Must Have)

### 1. Background Playback Implementation ⭐ **HIGHEST PRIORITY**
**Estimated Time:** 10-15 hours
**Complexity:** High (7/10)
**Why Critical:** App is useless without it - users expect to lock screen while listening

**Plan:** Already created at `/home/chris/.claude/plans/wild-wishing-spark.md`

**Approach:**
- Media3 MediaSessionService with custom ExoPlayer bridge
- Auto-generated MediaStyle notifications with lock screen controls
- Built-in audio focus handling (phone calls pause music)
- Battery optimized (Doze mode exempt)
- Bluetooth headset support

**Success Criteria:**
- Lock screen playback works
- Notification shows with controls
- Phone calls pause playback
- App switch doesn't stop music
- Rotation preserves playback state

**Blocks:** None - ready to start

---

### 2. Enable ProGuard/R8 for Release Builds 🔒
**Estimated Time:** 2-3 hours
**Complexity:** Medium (5/10)
**Why Critical:** Security & app size for Play Store release

**Current Status:** `minifyEnabled = false` in release build

**Tasks:**
- Enable `minifyEnabled = true` and `shrinkResources = true`
- Create ProGuard rules for gomobile AAR (critical!)
- Test release build doesn't crash
- Verify AAR classes not stripped
- Test on physical device

**ProGuard Rules Needed:**
```proguard
# Keep gomobile generated classes
-keep class player.** { *; }
-keepclassmembers class player.** { *; }
-keep interface player.** { *; }

# Keep callback interface methods
-keepclassmembers class * implements player.PlayerCallback {
    public void on*(...);
}
```

**Risk:** If rules incorrect, app will crash when calling Go player

**Blocks:** None - can do in parallel with background playback

---

### 3. Basic Error Handling & Recovery 🛡️
**Estimated Time:** 3-4 hours
**Complexity:** Medium (4/10)
**Why Critical:** Production apps need graceful failures

**Current Issues:**
- Generic `catch (e: Exception)` blocks with only Toast messages
- No retry mechanisms
- No connection timeout handling
- AudioTrack write errors logged but ignored

**Tasks:**
- Add connection timeout (30 seconds)
- Implement auto-reconnect on network glitch (max 3 retries)
- Better error messages (user-friendly)
- Handle AudioTrack write errors (rebuild track on repeated failures)
- Show SnackBar instead of Toast for critical errors

**Example:**
```kotlin
sealed class ConnectionError {
    object Timeout : ConnectionError()
    object NetworkUnavailable : ConnectionError()
    object ServerUnreachable : ConnectionError()
    data class AudioError(val message: String) : ConnectionError()
}

fun handleError(error: ConnectionError) {
    val message = when(error) {
        is Timeout -> "Connection timed out. Please try again."
        is NetworkUnavailable -> "No network connection. Check WiFi."
        is ServerUnreachable -> "Server not responding."
        is AudioError -> "Audio playback error: ${error.message}"
    }
    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        .setAction("Retry") { reconnect() }
        .show()
}
```

**Blocks:** None

---

### 4. Configuration Change Handling (Rotation) 🔄
**Estimated Time:** 2-3 hours
**Complexity:** Medium (5/10)
**Why Critical:** Users expect app to survive rotation

**Current Issues:**
- Server list lost on rotation
- Connection state lost
- Add Server dialog dismissed
- No ViewModel for state persistence

**Solution Options:**

**Option A: Quick Fix (for v1.0)**
- Handle config changes in manifest: `android:configChanges="orientation|screenSize"`
- Pros: Simple, works immediately
- Cons: Not recommended pattern, harder to maintain

**Option B: Proper Fix (recommended)**
- Extract state to ViewModel with SavedStateHandle
- Survives process death
- Better architecture
- Blocks: Requires some MVVM setup

**Recommendation:** Option A for v1.0, Option B during background playback refactor

---

### 5. Network Security Configuration 🌐
**Estimated Time:** 1 hour
**Complexity:** Low (2/10)
**Why Critical:** Security best practice, required for some networks

**Tasks:**
- Create `res/xml/network_security_config.xml`
- Allow cleartext for localhost/local networks only
- Block cleartext for all other domains
- Update AndroidManifest.xml

**Configuration:**
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">192.168.0.0</domain>
        <domain includeSubdomains="true">10.0.0.0</domain>
    </domain-config>
</network-security-config>
```

**Blocks:** None

---

## 🎯 Priority 2: HIGH for v1.0 (Should Have)

### 6. Basic Unit Tests 🧪
**Estimated Time:** 3-4 hours
**Complexity:** Medium (4/10)
**Why Important:** Safety net for refactoring, prevents regressions

**Minimum Test Coverage:**
- Server address validation logic
- ServerInfo data class equality
- Metadata formatting logic
- Server deduplication

**Test Structure:**
```
app/src/test/java/com/sendspindroid/
├── ServerValidatorTest.kt
├── ServerInfoTest.kt
└── MetadataFormatterTest.kt
```

**Dependencies Needed:**
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

**Blocks:** None - can do in parallel

---

### 7. UI/UX Polish ✨
**Estimated Time:** 4-5 hours
**Complexity:** Low (3/10)
**Why Important:** Professional appearance, better user experience

**Improvements:**
- **Loading indicators** during discovery
- **Empty state** message when no servers found
- **Connection status chip** (visual indicator)
- **Server list selection** (highlight connected server)
- **Metadata overflow handling** (ellipsize long titles)
- **Disable volume slider** when not connected

**Example Empty State:**
```xml
<TextView
    android:id="@+id/emptyStateText"
    android:text="No servers found.\n\nTap 'Discover Servers' to search your network."
    android:textAlignment="center"
    android:visibility="gone" />
```

**Blocks:** None

---

### 8. Input Validation & Sanitization 🔍
**Estimated Time:** 2 hours
**Complexity:** Low (3/10)
**Why Important:** Prevents crashes, better UX

**Current Issues:**
- Server address validation is basic (only checks non-empty)
- No IP/hostname format validation
- No sanitization of user inputs

**Improvements:**
- Regex validation for IP addresses
- Hostname format validation
- Port range validation (1-65535)
- Trim whitespace
- Prevent special characters in server names

**Example:**
```kotlin
fun validateServerAddress(address: String): ValidationResult {
    val parts = address.split(":")
    if (parts.size != 2) return ValidationResult.Error("Format must be host:port")

    val (host, portStr) = parts
    val port = portStr.toIntOrNull()
        ?: return ValidationResult.Error("Invalid port number")

    if (port !in 1..65535) return ValidationResult.Error("Port must be 1-65535")

    if (!isValidHostname(host) && !isValidIP(host)) {
        return ValidationResult.Error("Invalid hostname or IP address")
    }

    return ValidationResult.Success
}
```

**Blocks:** None

---

## 🎯 Priority 3: MEDIUM for v1.1 (Nice to Have)

### 9. DiffUtil for Server List ⚡
**Estimated Time:** 1-2 hours
**Complexity:** Low (2/10)
**Why Useful:** Better RecyclerView performance with many servers

**Current:** Using `notifyItemInserted()` manually

**Improvement:** Convert to ListAdapter with DiffUtil
- Automatic animations
- Efficient updates
- Less code

**Blocks:** None - easy optimization

---

### 10. Multicast Lock Improvements 🔧
**Estimated Time:** 1 hour
**Complexity:** Low (3/10)
**Why Useful:** Battery optimization, proper cleanup

**Issues:**
- Lock not released if discovery fails
- Held even when no discovery active

**Fix:** Use try-finally or structured concurrency

---

### 11. Connection Timeout Handling ⏱️
**Estimated Time:** 2 hours
**Complexity:** Medium (4/10)
**Why Useful:** Better UX when server unreachable

**Current:** No timeout, connection can hang indefinitely

**Solution:** Add timeout in Go player or wrap with withTimeout()

---

### 12. Debug Logging Optimization 📝
**Estimated Time:** 1 hour
**Complexity:** Low (2/10)
**Why Useful:** Performance, security

**Current:** Log.d() and Log.e() everywhere

**Solution:** Use Timber or BuildConfig check
```kotlin
if (BuildConfig.DEBUG) {
    Log.d(TAG, "Debug info")
}
```

---

## 🎯 Priority 4: LOW for v2.0+ (Future)

### 13. MVVM Architecture Migration 🏗️
**Estimated Time:** 8-12 hours
**Complexity:** High (8/10)

**Benefits:**
- Testable business logic
- Survives configuration changes
- Better separation of concerns
- StateFlow for reactive UI

**Should Do:** During or after background playback implementation

---

### 14. Jetpack Compose UI 🎨
**Estimated Time:** 15-20 hours
**Complexity:** High (8/10)

**Benefits:**
- Modern UI framework
- Less boilerplate
- Better animations
- Declarative UI

**Recommendation:** v2.0 feature, not v1.0

---

### 15. Room Database for Server History 💾
**Estimated Time:** 4-6 hours
**Complexity:** Medium (5/10)

**Benefits:**
- Persist favorite servers
- Remember last connected server
- Server connection history

---

### 16. Advanced Features 🚀
- Adaptive buffering based on network quality
- Equalizer/audio effects
- Playlist support
- Multi-room audio (play on multiple devices)
- Chromecast support
- Android Auto integration
- Wear OS companion app

---

## 📊 Recommended Implementation Order for v1.0

### Sprint 1: Core Functionality (Week 1)
**Goal:** Make app actually useful
1. ✅ Background Playback (10-15 hours) - **START HERE**
2. Enable ProGuard (2-3 hours) - parallel track
3. Network Security Config (1 hour)

**Total:** ~15-20 hours

### Sprint 2: Stability & Polish (Week 2)
**Goal:** Production-ready stability
4. Error Handling & Recovery (3-4 hours)
5. Configuration Change Handling (2-3 hours)
6. Input Validation (2 hours)
7. UI/UX Polish (4-5 hours)

**Total:** ~12-14 hours

### Sprint 3: Quality Assurance (Week 3)
**Goal:** Test everything
8. Basic Unit Tests (3-4 hours)
9. Manual testing on multiple devices
10. Bug fixes from testing (4-6 hours)
11. Performance testing
12. Release build validation

**Total:** ~10-15 hours

### Sprint 4: Release Prep (Week 4)
**Goal:** Ship it!
13. Play Store listing (screenshots, description)
14. Privacy policy
15. Final QA pass
16. v1.0 Release! 🎉

---

## 🎯 IMMEDIATE NEXT STEPS

### What to Work On RIGHT NOW:

**Option A: Background Playback (Recommended)**
- Most critical feature
- Plan already complete
- Estimated 10-15 hours
- Blocks other features

**Option B: Quick Wins First**
1. Network Security Config (1 hour)
2. Enable ProGuard (2-3 hours)
3. Input Validation (2 hours)
4. Then tackle background playback

**Recommendation:** **Option A** - Background playback is the core value proposition. Everything else can be parallel or follow.

---

## 📈 Success Metrics for v1.0

**Must Pass:**
- ✅ Background playback works reliably
- ✅ Survives rotation without losing state
- ✅ Handles network errors gracefully
- ✅ No memory leaks
- ✅ No crashes on release build
- ✅ Builds successfully in CI/CD
- ✅ APK size under 100MB
- ✅ Passes Google Play review

**Nice to Have:**
- Unit test coverage >30%
- Smooth UI (no jank)
- Fast app startup (<2 seconds)
- Beautiful UI with Material 3

---

## 💡 Notes

- **Background playback** is the gatekeeper - nothing else matters if users can't lock their screen
- **ProGuard** is critical for release - don't skip this
- **Testing** is investment in future velocity
- **MVVM migration** can happen gradually, doesn't need to block v1.0
- **Jetpack Compose** is v2.0+ material

**Current Assessment:**
- Core functionality: ✅ Working
- Critical bugs: ✅ Fixed in v0.0.3
- Production readiness: ⚠️ 60% (needs background playback + ProGuard)
- User experience: ⚠️ 70% (needs polish + error handling)

**Time to v1.0:** Estimated 4-6 weeks at current pace
