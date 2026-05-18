plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.jsoup)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.client.cio)
}
