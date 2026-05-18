dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)

    testImplementation(libs.bundles.testing)
}
