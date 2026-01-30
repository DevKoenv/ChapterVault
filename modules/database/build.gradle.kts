group = "${rootProject.group}.database"
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(project(":core"))

    // Exposed ORM - expose jdbc for Database class access by consumers
    api(libs.exposed.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)

    // Database drivers
    implementation(libs.h2.database)
    implementation(libs.sqlite.jdbc)
    implementation(libs.postgresql.jdbc)

    implementation(libs.kotlinx.coroutines.core)
}
