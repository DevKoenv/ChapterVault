dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.testing)
}
