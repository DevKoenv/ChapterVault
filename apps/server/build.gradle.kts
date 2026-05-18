plugins {
    application
}

val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("server")
    archiveClassifier.set("fat")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "dev.koenv.chaptervault.server.MainKt")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.build { dependsOn(fatJar) }

application {
    mainClass.set("dev.koenv.chaptervault.server.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kernel"))
    implementation(project(":extensions"))
    implementation(project(":infrastructure"))
    implementation(project(":interfaces"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.swagger)
    implementation(libs.bundles.koin)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.testing)
}
