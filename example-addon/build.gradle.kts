plugins {
    kotlin("jvm") version "2.3.0"
    id("io.github.goooler.shadow") version "8.1.8"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()

    // JitPack — uncomment this if you want to resolve core from JitPack instead of a local Maven install.
    // See GUIDE.md for instructions.
    // maven("https://jitpack.io")
}

dependencies {
    // ChapterVault core — compileOnly, provided by the host at runtime.
    // Requires a local Maven install. Run once in the ChapterVault repo:
    //   ./gradlew :core:publishToMavenLocal
    compileOnly("dev.koenv.ChapterVault:core:0.4.1")

    // JitPack alternative — comment out the line above and uncomment this instead.
    // Replace <version> with a git tag (e.g. "v0.4.1") or a commit hash.
    // Also uncomment the maven("https://jitpack.io") repository block above.
    // compileOnly("com.github.koenv.ChapterVault:core:<version>")

    // Logging — compileOnly because the host provides these at runtime.
    compileOnly("io.github.oshai:kotlin-logging-jvm:7.0.3")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // Add your own dependencies here — they WILL be bundled in the output JAR.
    // Example:
    // implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
