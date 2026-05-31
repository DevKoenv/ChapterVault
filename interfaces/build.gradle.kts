plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    // TODO: Replace ExtensionConfigRepository with an interface to remove this dependency
    implementation(project(":infrastructure"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
}
