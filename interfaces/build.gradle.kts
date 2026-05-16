plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.testing)
}
