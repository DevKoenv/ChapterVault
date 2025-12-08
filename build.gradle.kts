plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
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

tasks {
    val fatJar = register<Jar>("fatJar") {
        dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("standalone") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    build {
        dependsOn(fatJar) // Trigger fat jar creation during build
    }
}
