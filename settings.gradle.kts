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
include("orchestration")
include("storage")
include("database")
include("opds")
include("api")
include("app")

project(":core").projectDir = file("modules/core")
project(":connectors").projectDir = file("modules/connectors")
project(":orchestration").projectDir = file("modules/orchestration")
project(":storage").projectDir = file("modules/storage")
 project(":database").projectDir = file("modules/database")
project(":opds").projectDir = file("modules/opds")
project(":api").projectDir = file("modules/api")
project(":app").projectDir = file("modules/app")
