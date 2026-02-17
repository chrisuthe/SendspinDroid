plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidLibrary {
        namespace = "com.sendspindroid.shared"
        compileSdk = 36
        minSdk = 26

        withHostTest {
            isIncludeAndroidResources = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("io.ktor:ktor-client-core:3.1.1")
            implementation("io.ktor:ktor-client-websockets:3.1.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
        }
        getByName("androidHostTest") {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("io.mockk:mockk:1.13.16")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(21)
}
