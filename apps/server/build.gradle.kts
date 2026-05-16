plugins {
    application
}

application {
    mainClass.set("dev.chaptervault.server.MainKt")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":extensions"))
    implementation(project(":infrastructure"))
    implementation(project(":interfaces"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.koin)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.testing)
}
