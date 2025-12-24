# SendSpinDroid - Master Development Roadmap & Code Review

**Last Updated:** December 23, 2025
**Current Version:** v0.0.3
**Target:** v1.0 Production Release
**Project Type:** Android Audio Streaming Client

---

## 📋 Table of Contents

1. [Project Status](#project-status)
2. [Critical Roadmap - What to Build Next](#critical-roadmap)
3. [Code Review Summary](#code-review-summary)
4. [Detailed Feature Plans](#detailed-feature-plans)
5. [Implementation Sprints](#implementation-sprints)
6. [Success Metrics](#success-metrics)

---

## 🎯 Project Status

### ✅ Recently Completed (v0.0.3)
- ✅ Fix memory leak - AudioTrack not stopped on disconnect
- ✅ Fix race condition - Multiple audio streams
- ✅ Update to API 35 for Play Store compliance (August 2025 deadline)
- ✅ Fix AndroidManifest.xml syntax errors (CI/CD builds now pass)

### 📊 Current Assessment
- **Core Functionality:** ✅ Working (server discovery, connection, playback)
- **Critical Bugs:** ✅ Fixed in v0.0.3
- **Production Readiness:** ⚠️ 60% (needs background playback + ProGuard)
- **User Experience:** ⚠️ 70% (needs polish + error handling)
- **Code Quality:** 7/10 (solid fundamentals, needs architecture improvements)

### ⏱️ Time to v1.0
**Estimated:** 4-6 weeks at current pace

---

## 🚀 Critical Roadmap - What to Build Next

### 🎯 Priority 1: CRITICAL FOR v1.0 (Must Have)

#### 1. Background Playback Implementation ⭐ **HIGHEST PRIORITY - START HERE**
**Why:** App is useless without it - users must be able to lock screen while listening

**Status:** ✅ Plan complete at `/home/chris/.claude/plans/wild-wishing-spark.md`
**Estimated Time:** 10-15 hours
**Complexity:** High (7/10)

**Approach:**
- Media3 MediaSessionService with custom ExoPlayer bridge
- Auto-generated MediaStyle notifications with lock screen controls
- Built-in audio focus handling (phone calls pause music)
- Battery optimized (Doze mode exempt)
- Bluetooth headset support automatic

**Success Criteria:**
- ✅ Lock screen playback works
- ✅ Notification shows with play/pause controls
- ✅ Phone calls pause playback automatically
- ✅ App switch doesn't stop music
- ✅ Rotation preserves playback state

**Blocks:** None - ready to start immediately

---

#### 2. Enable ProGuard/R8 for Release Builds 🔒
**Why:** Security & APK size optimization for Play Store

**Estimated Time:** 2-3 hours
**Complexity:** Medium (5/10)
**Can run in parallel with background playback**

**Current Issue:** `minifyEnabled = false` - release APK is unobfuscated and 3x larger

**Tasks:**
- Enable `minifyEnabled = true` and `shrinkResources = true`
- Create ProGuard rules for gomobile AAR (critical!)
- Test release build thoroughly
- Verify AAR callback classes not stripped

**ProGuard Rules Required:**
```proguard
# Keep gomobile generated classes
-keep class player.** { *; }
-keepclassmembers class player.** { *; }
-keep interface player.** { *; }

# Keep callback interface methods (called from Go)
-keepclassmembers class * implements player.PlayerCallback {
    public void on*(...);
}
```

**Risk:** Incorrect rules will cause release build to crash when calling Go player

---

#### 3. Basic Error Handling & Recovery 🛡️
**Why:** Production apps must fail gracefully

**Estimated Time:** 3-4 hours
**Complexity:** Medium (4/10)

**Current Issues:**
- Generic `catch (e: Exception)` with only Toast messages
- No retry mechanisms
- No connection timeout (can hang indefinitely)
- AudioTrack write errors logged but ignored

**Implementation:**
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

**Tasks:**
- Add 30-second connection timeout
- Auto-reconnect on network glitch (max 3 retries)
- User-friendly error messages (Snackbar with retry action)
- Handle AudioTrack write failures (rebuild on repeated errors)

---

#### 4. Configuration Change Handling (Rotation) 🔄
**Why:** Users expect apps to survive rotation

**Estimated Time:** 2-3 hours
**Complexity:** Medium (5/10)

**Current Issues:**
- Server list lost on rotation
- Connection state lost
- Add Server dialog dismissed
- No ViewModel for state persistence

**Solution for v1.0 (Quick Fix):**
```xml
<!-- In AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize"
    android:exported="true">
```

**Pros:** Simple, works immediately
**Cons:** Not recommended Android pattern

**Better Solution (v1.1+):** Extract state to ViewModel with SavedStateHandle

---

#### 5. Network Security Configuration 🌐
**Why:** Security best practice, prevents MITM attacks

**Estimated Time:** 1 hour
**Complexity:** Low (2/10)

**Implementation:**
Create `res/xml/network_security_config.xml`:
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <!-- Allow cleartext only for local networks -->
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">192.168.0.0</domain>
        <domain includeSubdomains="true">10.0.0.0</domain>
    </domain-config>
</network-security-config>
```

Update `AndroidManifest.xml`:
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

---

### 🎯 Priority 2: HIGH for v1.0 (Should Have)

#### 6. Basic Unit Tests 🧪
**Estimated Time:** 3-4 hours
**Complexity:** Medium (4/10)

**Minimum Coverage:**
- Server address validation
- ServerInfo data class equality
- Metadata formatting
- Server deduplication logic

**Dependencies:**
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

---

#### 7. UI/UX Polish ✨
**Estimated Time:** 4-5 hours
**Complexity:** Low (3/10)

**Improvements Needed:**
- Loading indicators during discovery
- Empty state message ("No servers found")
- Connection status chip (visual indicator)
- Highlight connected server in list
- Truncate long metadata titles (ellipsize)
- Disable volume slider when not connected

---

#### 8. Input Validation & Sanitization 🔍
**Estimated Time:** 2 hours
**Complexity:** Low (3/10)

**Current:** Basic validation (only checks non-empty)

**Needed:**
- IP address regex validation
- Hostname format validation
- Port range check (1-65535)
- Whitespace trimming
- Special character prevention

---

### 🎯 Priority 3: MEDIUM for v1.1 (Nice to Have)

- **DiffUtil for Server List** (1-2 hrs) - Better RecyclerView performance
- **Multicast Lock Improvements** (1 hr) - Proper cleanup on failure
- **Connection Timeout** (2 hrs) - Better UX when server unreachable
- **Debug Logging** (1 hr) - Remove logs in release builds

---

### 🎯 Priority 4: LOW for v2.0+ (Future)

- **MVVM Architecture Migration** (8-12 hrs) - Testable, survives config changes
- **Jetpack Compose UI** (15-20 hrs) - Modern declarative UI
- **Room Database** (4-6 hrs) - Persist server history
- **Android Auto Integration** 🚗 - Research in progress
- **Advanced Features:**
  - Adaptive buffering
  - Equalizer/audio effects
  - Playlist support
  - Multi-room audio
  - Chromecast support
  - Wear OS companion app

---

## 📝 Code Review Summary

### Overall Assessment
**Code Quality:** 7/10
**Production Readiness:** 6.5/10

### ✅ Strengths

1. **Modern Kotlin Usage**
   - Proper null safety, data classes, lambda expressions
   - ViewBinding for type-safe view access
   - Coroutines with lifecycleScope (auto-cancellation)

2. **Clean Code Structure**
   - Logical separation of concerns
   - Consistent naming conventions
   - Good use of companion objects for constants

3. **Resource Management**
   - Explicit cleanup in onDestroy()
   - Reference-counted multicast lock
   - Proper coroutine cancellation

### ❌ Critical Issues (Fixed in v0.0.3)

1. ✅ **Memory Leak** - AudioTrack not stopped on disconnect → FIXED
2. ✅ **Race Condition** - Multiple audio streams could play → FIXED
3. ✅ **API 34** - Play Store requires API 35 by Aug 2025 → FIXED

### ⚠️ Remaining Issues

#### High Priority Issues

**1. No ProGuard/R8 Enabled**
- Release builds not obfuscated
- Easy to reverse engineer
- APK 2-3x larger than needed
- **Fix:** Enable minifyEnabled + create ProGuard rules

**2. No Background Playback**
- App stops when screen locks
- Can't use other apps while listening
- **Fix:** MediaSessionService implementation

**3. Configuration Changes Cause State Loss**
- Server list lost on rotation
- Connection state reset
- **Fix:** ViewModel or handle config changes

**4. Limited Error Handling**
- Generic exception catching
- No retry mechanisms
- Toast for errors (poor UX)
- **Fix:** Proper error types + Snackbar with retry

**5. No Testing**
- Zero unit tests
- Zero instrumented tests
- No safety net for refactoring
- **Fix:** Add basic test coverage

#### Medium Priority Issues

**6. Multicast Lock Not Released on Failure**
- Battery drain if discovery throws exception
- **Fix:** try-finally or structured concurrency

**7. Server Validation Weak**
- Accepts invalid input ("!!!:8080")
- No IP/hostname format checking
- **Fix:** Regex validation

**8. No Connection Timeout**
- Can hang indefinitely
- Poor UX when server unreachable
- **Fix:** 30-second timeout + retry

**9. AudioTrack Write Errors Ignored**
- Logged but playback continues
- Silent audio failures
- **Fix:** Error threshold + AudioTrack rebuild

#### Security Concerns

**10. No Network Security Config**
- Cleartext traffic allowed by default
- MITM attack vulnerability
- **Fix:** Restrict to localhost/local networks

**11. No Code Obfuscation**
- See #1 (ProGuard disabled)

**12. Debug Logging in Production**
- Sensitive data in logs (server addresses)
- **Fix:** Timber or BuildConfig.DEBUG checks

#### Performance Concerns

**13. AudioTrack Buffer Sizing**
- 4x multiplier is arbitrary
- Not optimized per device
- **Fix:** Make configurable, test on devices

**14. RecyclerView Without DiffUtil**
- Inefficient list updates
- **Fix:** ListAdapter with DiffUtil

**15. Server List Uses MutableList**
- O(n) search on every add
- **Fix:** HashSet for O(1) lookup

---

## 📈 Implementation Sprints for v1.0

### Sprint 1: Core Functionality (Week 1)
**Goal:** Make app actually useful

1. **Background Playback** (10-15 hrs) ⭐ START HERE
2. **Enable ProGuard** (2-3 hrs) - parallel track
3. **Network Security Config** (1 hr)

**Total:** 15-20 hours

---

### Sprint 2: Stability & Polish (Week 2)
**Goal:** Production-ready stability

4. **Error Handling & Recovery** (3-4 hrs)
5. **Configuration Change Handling** (2-3 hrs)
6. **Input Validation** (2 hrs)
7. **UI/UX Polish** (4-5 hrs)

**Total:** 12-14 hours

---

### Sprint 3: Quality Assurance (Week 3)
**Goal:** Test everything

8. **Basic Unit Tests** (3-4 hrs)
9. **Manual Testing** on multiple devices
10. **Bug Fixes** from testing (4-6 hrs)
11. **Performance Testing**
12. **Release Build Validation**

**Total:** 10-15 hours

---

### Sprint 4: Release Prep (Week 4)
**Goal:** Ship it!

13. **Play Store Listing** (screenshots, description)
14. **Privacy Policy**
15. **Final QA Pass**
16. **v1.0 Release!** 🎉

---

## 📊 Success Metrics for v1.0

### Must Pass (Hard Requirements)
- ✅ Background playback works reliably
- ✅ Survives rotation without losing state
- ✅ Handles network errors gracefully
- ✅ No memory leaks
- ✅ No crashes on release build
- ✅ Builds successfully in CI/CD
- ✅ APK size under 100MB
- ✅ Passes Google Play review

### Nice to Have (Soft Goals)
- Unit test coverage >30%
- Smooth UI (no jank)
- Fast app startup (<2 seconds)
- Beautiful UI with Material 3

---

## 🎯 IMMEDIATE NEXT STEPS

### Recommended: Start Background Playback NOW ⭐

**Why?**
- Most critical feature - blocks v1.0 release
- App is useless without it
- Plan is complete and ready
- Everything else can run in parallel

**How?**
1. Review plan: `/home/chris/.claude/plans/wild-wishing-spark.md`
2. Start Phase 1: Add Media3 dependencies
3. Implement GoPlayerDataSource bridge
4. Create PlaybackService
5. Refactor MainActivity
6. Test thoroughly

**Alternative:**
If you want quick wins first (5-6 hours total):
1. Network Security Config (1 hr)
2. Enable ProGuard (2-3 hrs)
3. Input Validation (2 hrs)
4. Then tackle background playback

---

## 📚 Additional Resources

### Code Review Details
- **Full QA Review:** `CODE_REVIEW_QA.md` (will be archived)
- **Full Android Review:** `CODE_REVIEW_ANDROID.md` (will be archived)
- **Background Playback Plan:** `/home/chris/.claude/plans/wild-wishing-spark.md`

### Setup & Development
- **Quick Start:** `QUICKSTART.md`
- **Setup Guide:** `SETUP_AND_TEST.md`
- **Project Summary:** `PROJECT_SUMMARY.md`
- **Integration Guide:** `INTEGRATION.md`

---

## 💡 Key Insights

### What Makes This Project Good
- ✅ Clean Kotlin code with modern idioms
- ✅ Proper lifecycle management
- ✅ Working gomobile integration (non-trivial!)
- ✅ Material Design 3 components
- ✅ Fixed critical bugs (memory leaks, race conditions)

### What Needs Work
- ⚠️ Architecture (Activity does everything)
- ⚠️ No testing whatsoever
- ⚠️ Background playback missing (critical feature)
- ⚠️ Release build not optimized (ProGuard)
- ⚠️ Error handling too generic

### Strategic Recommendations
1. **Do First:** Background playback (enables all other use cases)
2. **Do in Parallel:** ProGuard, network security (quick wins)
3. **Do After:** MVVM migration during background playback refactor
4. **Do Later:** Jetpack Compose (v2.0), advanced features

**Bottom Line:** You have a solid foundation. Background playback is the only thing blocking a useful v1.0 release. Everything else is optimization.

---

**Last Updated:** December 23, 2025
**Next Review:** After background playback implementation
