plugins {
    kotlin("jvm") version "2.0.10"
    application
}

group = "dev.koenv"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ----------------------------
    // Kotlin Core
    // ----------------------------
    implementation(libs.kotlin.stdlib)

    // ----------------------------
    // Coroutines
    // ----------------------------
    implementation(libs.coroutines.core)

    // ----------------------------
    // Ktor HTTP client
    // ----------------------------
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ----------------------------
    // Database
    // ----------------------------
    implementation(libs.sqlite.jdbc)

    // ----------------------------
    // Testing
    // ----------------------------
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.koenv.chaptervault.ChapterVaultApp")
}
