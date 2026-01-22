group = "${rootProject.group}.database"
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.h2.database)
    
    implementation(libs.kotlinx.coroutines.core)
}
