// Sendspin conformance harness client adapter.
//
// A small JVM CLI that drives this app's shared protocol layer
// (MessageBuilder/MessageParser/BinaryMessageParser/SendspinTimeFilter)
// against the official Sendspin/conformance harness. Built as a fat jar
// and launched by the harness via ci/conformance/sendspindroid_client.py.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("conformance-client")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.sendspindroid.conformance.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
}
