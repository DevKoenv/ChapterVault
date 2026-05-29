plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

val coverageThreshold = 65 // raise this as test coverage improves

kover {
    merge {
        subprojects()
    }

    reports {
        total {
            html {
                onCheck = false
            }
            xml {
                onCheck = false
                xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
            }
            verify {
                onCheck = true
                rule {
                    minBound(coverageThreshold)
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xjsr305=strict"
            )
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.register("coverage") {
    group = "verification"
    description = "Generates HTML and XML coverage reports."
    dependsOn("koverHtmlReport", "koverXmlReport")
}
