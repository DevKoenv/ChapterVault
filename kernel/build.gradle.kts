plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: types appear in kernel's public interface signatures
    api(project(":shared"))
    api(libs.ktor.client.core)
    api(libs.slf4j.api)
    api(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.client.mock)
}
