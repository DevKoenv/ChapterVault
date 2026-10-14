plugins {
    id("chaptervault.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(libs.xmlutil.serialization)
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    // the route tests use the in-memory connector registry; production code in this module must not know :infrastructure
    testImplementation(project(":infrastructure"))
}
