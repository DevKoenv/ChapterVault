dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.bundles.exposed)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml)
    implementation(libs.jbcrypt)

    testImplementation(libs.bundles.testing)
}
