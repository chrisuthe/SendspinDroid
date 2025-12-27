// Top-level build file where you can add configuration options common to all sub-projects/modules.
// This file uses Kotlin DSL (.kts) instead of Groovy - modern Android best practice since 2023

plugins {
    // Android Gradle Plugin (AGP) - manages Android build process
    // Version 8.13.2 is current stable (Dec 2025), supports API 36
    id("com.android.application") version "8.13.2" apply false

    // Kotlin Android plugin - enables Kotlin compilation for Android
    // Version 2.1.0 is compatible with AGP 8.13.x
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
