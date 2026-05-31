dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.cio)
}
