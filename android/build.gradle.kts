// Top-level build file where you can add configuration options common to all sub-projects/modules.
// This file uses Kotlin DSL (.kts) instead of Groovy - modern Android best practice since 2023

plugins {
    // Android Gradle Plugin (AGP) - manages Android build process
    // Version 8.7.3 is current as of Dec 2024
    // Note: For 2025, consider upgrading to AGP 8.13+ for API 36 support
    id("com.android.application") version "8.7.3" apply false

    // Kotlin Android plugin - enables Kotlin compilation for Android
    // Version 2.1.0 is compatible with AGP 8.7.3
    // Note: Kotlin 2.2+ available in 2025 with performance improvements
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false

    // "apply false" means plugins are not applied to this project, only made available to subprojects
    // Actual application happens in app/build.gradle.kts
}
