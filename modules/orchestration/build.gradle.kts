group = "${rootProject.group}.orchestration"
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
