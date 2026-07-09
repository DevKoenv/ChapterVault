plugins {
    application
    alias(libs.plugins.shadow)
}

kover {
    disable()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }

application {
    mainClass.set("dev.koenv.chaptervault.server.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(project(":infrastructure"))
    implementation(project(":interfaces"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.swagger)
    implementation(libs.bundles.koin)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.testing)
}
