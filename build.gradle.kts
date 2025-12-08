plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    application
}

group = "dev.koenv"
version = "RCv1.0.0"

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
    implementation(libs.kotlin.reflect)

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
    // Jackson
    // ----------------------------
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // ----------------------------
    // Kotlinx serialization
    // ----------------------------
    implementation(libs.kotlinx.serialization.core)

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
