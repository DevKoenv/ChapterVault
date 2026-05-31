plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":kernel"))
    api(project(":shared"))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
}
