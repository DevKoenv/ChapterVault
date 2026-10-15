plugins {
    id("chaptervault.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.bundles.exposed)
    implementation(libs.sqlite.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.sqlite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml)
    implementation(libs.jbcrypt)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
}
