plugins {
    id("chaptervault.kotlin-library")
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.buildconfig)
}

// The Docker build context has no .git (and the builder image no git); asking for it would fail the configuration cache.
val gitRevision: String =
    if (rootProject.file(".git").exists()) {
        try {
            providers
                .exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                }.standardOutput.asText
                .get()
                .trim()
        } catch (e: Exception) {
            "unknown"
        }
    } else {
        "unknown"
    }

buildConfig {
    packageName("dev.koenv.chaptervault.server.generated")
    buildConfigField("String", "VERSION", "\"${rootProject.version}\"")
    buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
    buildConfigField("String", "REVISION", "\"$gitRevision\"")
    buildConfigField("String", "PROJECT_NAME", "\"${project.name}\"")
    buildConfigField("String", "REPOSITORY_URL", "\"https://github.com/DevKoenv/ChapterVault\"")
}

kover {
    disable()
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }

application {
    mainClass.set("dev.koenv.chaptervault.server.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(project(":infrastructure"))
    implementation(project(":interfaces"))
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.swagger)
    implementation(libs.bundles.koin)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
}
