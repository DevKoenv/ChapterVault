dependencies {
    implementation(project(":kernel"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.testing)
}
