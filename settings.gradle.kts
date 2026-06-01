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

    ":infrastructure",
    ":interfaces",
    ":sdk",
    ":apps:server"
)
