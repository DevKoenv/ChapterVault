group = "dev.koenv.chaptervault"
version = "1.0.0"

plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.buildconfig) apply false
}

val coverageThreshold = 70 // raise this as test coverage improves

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

detekt {
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/main/kotlin/**/*.kt", "**/src/main/kotlin/**/*.kts")
            exclude("**/build/**")
        },
    )
}


tasks.register("coverage") {
    group = "verification"
    description = "Generates HTML and XML coverage reports."
    dependsOn("koverHtmlReport", "koverXmlReport")
}

tasks.register("check") {
    group = "verification"
    description = "Run all checks including detekt analysis."
    dependsOn("detekt")
}
