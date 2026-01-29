group = "${rootProject.group}.storage"
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
}
