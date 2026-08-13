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

    // Plain JVM target so the protocol layer (MessageBuilder/MessageParser/
    // BinaryMessageParser/SendspinTimeFilter) can be driven by the Sendspin
    // conformance harness adapter (:conformance-client) on a desktop JVM.
    jvm()

    // Both targets are JVM-based, but the Noise layer needs primitives the JDK
    // cannot supply on Android: there is no XDH provider for X25519, and
    // ChaCha20-Poly1305 only arrives at API 28 while minSdk is 26. So the crypto
    // sits on BouncyCastle's low-level org.bouncycastle.crypto.* API (never JCA -
    // registering a provider collides with Android's repackaged
    // com.android.org.bouncycastle).
    //
    // This intermediate group lets androidMain and jvmMain share one copy of
    // that code instead of duplicating actuals in both. Declared through the
    // hierarchy template rather than explicit dependsOn() calls, because a
    // manual edge silently disables the default template for the whole module.
    // Applied explicitly because the dependsOn() edges below would otherwise
    // suppress the automatic application of the default template for the whole
    // module - silently, and taking the standard androidHostTest wiring with it.
    // The template's withAndroidTarget() does not match this module's
    // `androidLibrary` target, so the edge is wired by hand.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmShared = create("jvmShared") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.bouncycastle:bcprov-jdk18on:1.80")
            }
        }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)

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
        jvmMain.dependencies {
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
