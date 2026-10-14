plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    filter {
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjsr305=strict",
            // CI and the Docker image run JDK 21. Compiling against the JDK 21 API on any newer local JDK means a build
            // that is green here cannot fail there because of a method that only exists in a later release.
            "-Xjdk-release=21",
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // a failure on a CI runner is otherwise only a line number in the log
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
