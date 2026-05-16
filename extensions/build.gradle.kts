dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.testing)
}
