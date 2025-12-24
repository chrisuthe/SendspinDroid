// App-level build configuration
// Defines how the Android app module is compiled and packaged

plugins {
    // Android application plugin - enables Android-specific build tasks
    id("com.android.application")

    // Kotlin Android plugin - enables Kotlin source compilation
    id("org.jetbrains.kotlin.android")

    // TODO: Consider adding kotlin-parcelize for efficient Parcelable implementation
    // TODO: Consider adding kotlin-kapt if Room or Dagger is added
}

// Java toolchain configuration
// Ensures consistent Java version across different environments
java {
    toolchain {
        // Java 17 is LTS and required for modern Android development
        // Compatible with AGP 8.x and Kotlin 2.x
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Kotlin JVM target configuration
// Must match Java version for bytecode compatibility
kotlin {
    jvmToolchain(17)
}

android {
    // Namespace replaces package attribute in AndroidManifest.xml (AGP 7.0+)
    // Used for R class generation and resource ID namespacing
    namespace = "com.sendspindroid"

    // compileSdk: SDK version used during compilation
    // Version 35 = Android 15 (released 2024)
    // Fix Issue #3: Updated to match targetSdk 35 for consistency
    // Must be >= targetSdk to avoid build errors
    compileSdk = 35

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

        // targetSdk: Version app is tested against
        // Determines which platform behaviors apply
        // API 35 = Android 15
        // Fix Issue #3: Updated to API 35 for Google Play compliance (required Aug 2025)
        // Note: May require edge-to-edge UI handling (WindowInsets, system bars)
        targetSdk = 35

        // versionCode: Integer version for Google Play (auto-increment for each release)
        // Users never see this, but must increase with each update
        versionCode = 1

        // versionName: User-visible version string
        // Follows semantic versioning (major.minor.patch)
        versionName = "1.0"

        // TODO: Add testInstrumentationRunner for UI tests
        // testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Release build configuration (for production APKs/AABs)
        release {
            // Code minification (shrinking, obfuscation, optimization)
            // Currently disabled for easier debugging
            // TODO: Enable for production to reduce APK size and improve security
            isMinifyEnabled = false

            // ProGuard/R8 configuration files
            // - proguard-android-optimize.txt: Android's default rules with optimizations
            // - proguard-rules.pro: App-specific rules (keep gomobile classes)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // TODO: Add signing configuration for release builds
            // TODO: Enable isMinifyEnabled = true for production
            // TODO: Add isShrinkResources = true to remove unused resources
        }

        // Debug build type is implicit with default settings
        // Consider adding custom debug config for:
        // - applicationIdSuffix = ".debug" (install alongside release)
        // - debuggable = true (default, allows debugger attachment)
    }

    // Java bytecode version compatibility
    // Must match toolchain version (17)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin compiler JVM target
    // Generates bytecode compatible with Java 17
    kotlinOptions {
        jvmTarget = "17"

        // TODO: Consider enabling these Kotlin compiler options:
        // freeCompilerArgs += listOf(
        //     "-opt-in=kotlin.RequiresOptIn", // Enable opt-in APIs
        //     "-Xjvm-default=all" // Generate Java 8+ default methods
        // )
    }

    // Build features configuration
    buildFeatures {
        // ViewBinding: Generate binding classes for layouts
        // Provides type-safe view access without findViewById()
        // Recommended over DataBinding for simple use cases
        viewBinding = true

        // TODO: Consider enabling Compose for modern UI
        // compose = true

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
    // AndroidX Core KTX - Kotlin extensions for Android framework APIs
    // Version 1.12.0 (Dec 2023)
    // TODO 2025: Update to 1.13.x or 1.15.x for latest extensions
    implementation("androidx.core:core-ktx:1.12.0")

    // AppCompat - Backward-compatible implementations of new Android features
    // Provides Material theming, ActionBar, and platform compatibility
    // Version 1.6.1 (Dec 2023)
    // TODO 2025: Update to 1.7.x for latest features
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Design Components - Google's Material Design 3 library
    // Provides Material widgets (Button, Card, Slider, TextInput, etc.)
    // Version 1.11.0 (Jan 2024)
    // TODO 2025: Update to 1.12.x or later for Material 3 Expressive features
    implementation("com.google.android.material:material:1.11.0")

    // ConstraintLayout - Flexible layout manager for complex UIs
    // More performant than nested LinearLayouts
    // Version 2.1.4 (Jul 2022)
    // TODO: Update to 2.2.x when stable (beta as of 2024)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle Runtime KTX - Lifecycle-aware components and coroutine support
    // Provides lifecycleScope for safe coroutine management
    // Version 2.7.0 (Jan 2024)
    // TODO 2025: Update to 2.8.x or 2.9.x
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Kotlin Coroutines for Android - Structured concurrency primitives
    // Provides Dispatchers.Main, Dispatchers.IO for threading
    // Version 1.7.3 (Sep 2023)
    // TODO 2025: Update to 1.8.x or 1.9.x for performance improvements
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Player module - Contains gomobile-generated AAR (player.aar)
    // This is a local module dependency, not a remote Maven artifact
    // The :player module is defined in settings.gradle.kts
    // It provides the JNI bridge to Go code
    implementation(project(":player"))

    // Media3 - Modern media playback framework for background playback
    // Version 1.6.0 (2024)
    // Provides MediaSession for background playback and media controls
    implementation("androidx.media3:media3-session:1.6.0")
    // ExoPlayer integration for advanced media playback features
    implementation("androidx.media3:media3-exoplayer:1.6.0")
    // Common Media3 types and interfaces
    implementation("androidx.media3:media3-common:1.6.0")

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

    // TODO: Add OkHttp/Retrofit if REST API is added
    // implementation("com.squareup.okhttp3:okhttp:4.12.x")
    // implementation("com.squareup.retrofit2:retrofit:2.11.x")

    // TODO: Add testing dependencies
    // testImplementation("junit:junit:4.13.2")
    // androidTestImplementation("androidx.test.ext:junit:1.2.x")
    // androidTestImplementation("androidx.test.espresso:espresso-core:3.6.x")
}
