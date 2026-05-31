pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "chaptervault"

include(
    ":shared",
    ":kernel",
    ":extensions",
    ":infrastructure",
    ":interfaces",
    ":sdk",
    ":apps:server"
)
