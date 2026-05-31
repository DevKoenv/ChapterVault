plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":sdk"))
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.jsoup)
    implementation(libs.snakeyaml)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(project(":infrastructure"))
}
