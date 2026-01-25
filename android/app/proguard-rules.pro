# ProGuard/R8 rules for SendSpinDroid
# These rules prevent R8 from removing classes accessed via reflection

# ============================================================================
# OkHttp + OkIO (WebSocket connections)
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# OkHttp platform adapters
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================================
# Java-WebSocket (server-initiated connections)
# ============================================================================
-keep class org.java_websocket.** { *; }
-keepclassmembers class * extends org.java_websocket.server.WebSocketServer {
    <init>(...);
}
-keepclassmembers class * extends org.java_websocket.client.WebSocketClient {
    <init>(...);
}

# ============================================================================
# Kotlin Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Kotlin Serialization (if used in future)
# ============================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ============================================================================
# AndroidX Media3 (MediaSession, MediaController)
# ============================================================================
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Media3 session callbacks must be preserved
-keepclassmembers class * extends androidx.media3.session.MediaLibraryService {
    <methods>;
}
-keepclassmembers class * extends androidx.media3.session.MediaSession$Callback {
    <methods>;
}

# ============================================================================
# Coil (Image loading with reflection-based decoders)
# ============================================================================
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**

# ============================================================================
# AndroidX Lifecycle (ViewModel, LiveData callbacks)
# ============================================================================
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * implements androidx.lifecycle.LifecycleObserver {
    <methods>;
}

# ============================================================================
# AndroidX Preference (settings reflection)
# ============================================================================
-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.preference.PreferenceFragmentCompat { *; }

# ============================================================================
# Keep app's model/data classes (if JSON parsing is added)
# ============================================================================
# -keep class com.sendspindroid.model.** { *; }

# ============================================================================
# Android components (Activities, Services, Receivers)
# ============================================================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ============================================================================
# Keep native method names (for JNI)
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================================
# Debugging: Keep source file names and line numbers for crash reports
# ============================================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
