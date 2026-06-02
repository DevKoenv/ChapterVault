plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(project(":infrastructure"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
}
