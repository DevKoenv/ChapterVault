dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.bundles.exposed)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml)

    testImplementation(libs.bundles.testing)
}
