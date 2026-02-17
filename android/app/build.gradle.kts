// App-level build configuration
// Defines how the Android app module is compiled and packaged

plugins {
    // Android application plugin - enables Android-specific build tasks
    // AGP 9.0+ includes built-in Kotlin support, no separate plugin needed
    id("com.android.application")

    // Compose compiler plugin - required for Kotlin 2.0+ with Jetpack Compose
    id("org.jetbrains.kotlin.plugin.compose")

    // TODO: Consider adding kotlin-parcelize for efficient Parcelable implementation
    // TODO: Consider adding kotlin-kapt if Room or Dagger is added
}

// Java toolchain configuration
// Ensures consistent Java version across different environments
java {
    toolchain {
        // Java 21 is current LTS (Sept 2023), bundled with Android Studio
        // Compatible with AGP 8.x, Kotlin 2.x, and Gradle 9.x
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Kotlin JVM target configuration
// Must match Java version for bytecode compatibility
kotlin {
    jvmToolchain(21)
}

// Check if debug keystore exists (for local development signing)
// On CI, this file won't exist and release builds will be unsigned
val debugKeystoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
val hasDebugKeystore = debugKeystoreFile.exists()

android {
    // Namespace replaces package attribute in AndroidManifest.xml (AGP 7.0+)
    namespace = "com.sendspindroid"

    // compileSdk 36 required by androidx.core:core-ktx:1.17.0
    compileSdk = 36

    // Signing configuration for release builds
    // Only configure if keystore exists (allows CI to build unsigned APKs)
    signingConfigs {
        if (hasDebugKeystore) {
            create("release") {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        // Unique identifier for the app on Google Play and devices
        // Must remain consistent across updates
        applicationId = "com.sendspindroid"

        // minSdk: Minimum Android version supported
        // API 26 = Android 8.0 Oreo (Aug 2017)
        // Covers ~95% of active devices as of 2024
        // Chosen for: AudioTrack improvements, better background execution
        // TODO 2025: Consider raising to API 29 (Android 10) for security
        minSdk = 26

        // targetSdk 36 = Android 16
        targetSdk = 36

        // versionCode: Encoded semantic version for Google Play.
        // Formula: MAJOR * 10000 + MINOR * 100 + PATCH
        // Ranges: MAJOR 0-9999, MINOR 0-99, PATCH 0-99
        // Example: 2.0.0 = 20000, 2.1.3 = 20103, 10.5.22 = 100522
        versionCode = 20000

        // versionName: User-visible version string
        // Follows semantic versioning (major.minor.patch[-prerelease])
        versionName = "2.0.0-Beta1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Release build configuration (for production APKs/AABs)
        release {
            // R8 code minification (shrinking, obfuscation, optimization)
            // Reduces APK size by ~30-50% and obfuscates code
            isMinifyEnabled = true

            // Remove unused resources (images, layouts, strings)
            // Works with R8 to identify what's actually used
            isShrinkResources = true

            // ProGuard/R8 configuration files
            // - proguard-android-optimize.txt: Android's default rules with optimizations
            // - proguard-rules.pro: App-specific rules for our dependencies
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sign release builds with debug key for testing (if keystore exists)
            // On CI, signing is handled via injected parameters
            if (hasDebugKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Debug build type is implicit with default settings
        // Consider adding custom debug config for:
        // - applicationIdSuffix = ".debug" (install alongside release)
        // - debuggable = true (default, allows debugger attachment)
    }

    // Java bytecode version compatibility
    // Must match toolchain version (21)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Kotlin compiler options are configured via kotlin { jvmToolchain(21) } above
    // which automatically sets JVM target to match the toolchain version

    // Build features configuration
    buildFeatures {
        // ViewBinding: Generate binding classes for layouts
        // Provides type-safe view access without findViewById()
        // Recommended over DataBinding for simple use cases
        viewBinding = true

        // Jetpack Compose: Modern declarative UI toolkit
        // Used for Artist/Album detail screens (incremental migration)
        compose = true

        // buildConfig = false (default in AGP 8.0+)
        // Enable if you need BuildConfig.DEBUG or custom build config fields
    }

    // TODO: Add packaging options to handle native library conflicts
    // packaging {
    //     resources {
    //         excludes += "/META-INF/{AL2.0,LGPL2.1}"
    //     }
    // }
}


dependencies {
    // Shared KMP module
    implementation(project(":shared"))

    // Kotlinx Serialization JSON - used for protocol message parsing in :app
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // AndroidX Core KTX - Kotlin extensions for Android framework APIs
    implementation("androidx.core:core-ktx:1.17.0")

    // AppCompat - Backward-compatible implementations of new Android features
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Design Components - Google's Material Design 3 library
    implementation("com.google.android.material:material:1.13.0")

    // ConstraintLayout - Flexible layout manager for complex UIs
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Lifecycle Runtime KTX - Lifecycle-aware components and coroutine support
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    // Kotlin Coroutines for Android - Structured concurrency primitives
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // OkHttp - HTTP client with WebSocket support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Media3 - MediaSession for background playback and system integration
    // Note: ExoPlayer removed - using native SyncAudioPlayer for audio output
    implementation("androidx.media3:media3-session:1.9.0")
    implementation("androidx.media3:media3-common:1.9.0")

    // Coil - Kotlin-first image loading library for album artwork
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ========== Jetpack Compose ==========
    // BOM (Bill of Materials) ensures all Compose libraries use compatible versions
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)

    // Material Design 3 for Compose - modern theming and components
    implementation("androidx.compose.material3:material3")

    // Material3 Adaptive - WindowSizeClass detection and NavigationSuiteScaffold
    // (auto-switches between BottomNav, NavigationRail, and NavigationDrawer)
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")

    // Foundation - core layout primitives (Column, Row, Box, LazyColumn)
    implementation("androidx.compose.foundation:foundation")

    // UI Tooling - @Preview support and debugging tools
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity Compose - setContent {} in Activity/Fragment
    implementation("androidx.activity:activity-compose:1.10.0")

    // Lifecycle Compose - collectAsStateWithLifecycle()
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Compose Runtime LiveData - observeAsState() for LiveData in Compose
    implementation("androidx.compose.runtime:runtime-livedata")

    // Palette - Extract prominent colors from images for dynamic theming
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Preference - Settings UI with PreferenceFragmentCompat
    implementation("androidx.preference:preference-ktx:1.2.1")

    // SwipeRefreshLayout - Pull-to-refresh for lists
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // ViewPager2 - Tab swiping for Library browser
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Fragment KTX - activityViewModels() and other fragment extensions
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    // ViewModel KTX - Kotlin extensions for ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")

    // Java-WebSocket - Lightweight WebSocket server for server-initiated connections
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // ========== Remote Access (WebRTC + QR Scanning) ==========

    // WebRTC for Android - Enables remote connections via Music Assistant
    // Uses DataChannel for SendSpin protocol over peer-to-peer connection
    // Using Stream's maintained fork of Google WebRTC
    implementation("io.getstream:stream-webrtc-android:1.3.4")

    // ML Kit Barcode Scanning - QR code scanning for Remote ID input
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX - Modern camera API for QR code scanner
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // RECOMMENDED ADDITIONS FOR V2:
    // TODO: Add RecyclerView explicitly (currently transitive via Material)
    // implementation("androidx.recyclerview:recyclerview:1.3.x")

    // TODO: Add ViewModel for MVVM architecture
    // implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.x")

    // TODO: Add Room for local server persistence
    // implementation("androidx.room:room-runtime:2.6.x")
    // implementation("androidx.room:room-ktx:2.6.x")
    // kapt("androidx.room:room-compiler:2.6.x")

    // TODO: Add DataStore for preferences (replaces SharedPreferences)
    // implementation("androidx.datastore:datastore-preferences:1.1.x")

    // TODO: Add WorkManager for background sync
    // implementation("androidx.work:work-runtime-ktx:2.9.x")

    // TODO: Add Timber for better logging
    // implementation("com.jakewharton.timber:timber:5.0.x")

    // TODO: Add Retrofit if REST API is added
    // implementation("com.squareup.retrofit2:retrofit:2.11.x")

    // Unit tests (JVM, no emulator needed)
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Instrumented tests (emulator-based)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
