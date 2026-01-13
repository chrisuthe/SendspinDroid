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

        // versionCode: Integer version for Google Play (auto-increment for each release)
        // Users never see this, but must increase with each update
        versionCode = 13

        // versionName: User-visible version string
        // Follows semantic versioning (major.minor.patch)
        versionName = "1.0.13"

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
            // - proguard-rules.pro: App-specific rules
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sign release builds with debug key for testing (if keystore exists)
            // On CI without keystore, builds will be unsigned
            // TODO: Replace with production keystore for Play Store release
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

    // Kotlin compiler JVM target
    // Generates bytecode compatible with Java 21
    kotlinOptions {
        jvmTarget = "21"

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

    // Palette - Extract prominent colors from images for dynamic theming
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Preference - Settings UI with PreferenceFragmentCompat
    implementation("androidx.preference:preference-ktx:1.2.1")

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

    // TODO: Add testing dependencies
    // testImplementation("junit:junit:4.13.2")
    // androidTestImplementation("androidx.test.ext:junit:1.2.x")
    // androidTestImplementation("androidx.test.espresso:espresso-core:3.6.x")
}
