plugins {
    id("chaptervault.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.xmlutil.serialization)
    implementation(project(":infrastructure"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
}
