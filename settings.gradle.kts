rootProject.name = "ChapterVault"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include("core")
include("connectors")
include("download")
include("orchestration")
include("storage")
include("opds")
include("api")
include("runner")
include("app")

project(":core").projectDir = file("modules/core")
project(":connectors").projectDir = file("modules/connectors")
project(":download").projectDir = file("modules/download")
project(":orchestration").projectDir = file("modules/orchestration")
project(":storage").projectDir = file("modules/storage")
project(":opds").projectDir = file("modules/opds")
project(":api").projectDir = file("modules/api")
project(":runner").projectDir = file("modules/runner")
project(":app").projectDir = file("modules/app")
