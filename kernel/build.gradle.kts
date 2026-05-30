dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)

    testImplementation(libs.bundles.testing)
}
