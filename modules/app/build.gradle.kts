group = "${rootProject.group}.app"
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.koenv.chaptervault.app.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":connectors"))
    implementation(project(":orchestration"))
    implementation(project(":storage"))
    implementation(project(":api"))
    implementation(project(":opds"))
    implementation(project(":database"))
    
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.snakeyaml)
}