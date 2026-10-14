group = "dev.koenv.chaptervault"
// the release workflow passes -PreleaseVersion=<tag without v>, so a pre-release tag builds a jar that knows it is a pre-release
version = (findProperty("releaseVersion") as String?) ?: "1.0.0"

plugins {
    base
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
    baseline = file("$rootDir/detekt-baseline.xml")
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

// The documented one-way module graph: shared <- kernel <- infrastructure, and interfaces/sdk may only know shared and kernel.
// Only main-source dependencies count (api, implementation, runtimeOnly, compileOnly); tests may reach further.
abstract class VerifyModuleGraph : DefaultTask() {
    @get:Input
    abstract val declared: MapProperty<String, Set<String>>

    @get:Input
    abstract val allowed: MapProperty<String, Set<String>>

    @TaskAction
    fun verify() {
        val violations =
            declared.get().flatMap { (module, deps) ->
                val permitted = allowed.get().getValue(module)
                (deps - permitted).map { "$module must not depend on $it (allowed: ${permitted.ifEmpty { setOf("nothing") }})" }
            }
        if (violations.isNotEmpty()) {
            throw GradleException("Module graph violated:" + violations.joinToString("") { "${System.lineSeparator()}  - $it" })
        }
    }
}

val moduleRules =
    mapOf(
        ":shared" to emptySet(),
        ":kernel" to setOf(":shared"),
        ":infrastructure" to setOf(":shared", ":kernel"),
        ":interfaces" to setOf(":shared", ":kernel"),
        ":sdk" to setOf(":shared", ":kernel"),
    )

val verifyModuleGraph =
    tasks.register<VerifyModuleGraph>("verifyModuleGraph") {
        group = "verification"
        description = "Fails when a module depends on a module it may not know about (for example :interfaces on :infrastructure)."
        allowed.set(moduleRules)
        declared.set(
            provider {
                moduleRules.keys.associateWith { path ->
                    val module = project(path)
                    listOf("api", "implementation", "runtimeOnly", "compileOnly")
                        .flatMap { name -> module.configurations.findByName(name)?.dependencies.orEmpty() }
                        .filterIsInstance<ProjectDependency>()
                        .map { it.path }
                        .toSet()
                }
            },
        )
    }

// `check` is the standard lifecycle task from the base plugin (subprojects' check runs tests and ktlint); detekt is an extra dependency
tasks.named("check") {
    dependsOn("detekt", verifyModuleGraph)
}
