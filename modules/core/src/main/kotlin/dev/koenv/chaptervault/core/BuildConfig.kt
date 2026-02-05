package dev.koenv.chaptervault.core

/**
 * Build configuration constants.
 *
 * Note: VERSION is the single source of truth for the application version.
 * Update the version in the root build.gradle.kts file, then run:
 * ./gradlew generateBuildConfig
 *
 * Or manually update this file when changing versions.
 */
object BuildConfig {
    /**
     * Application version - must match root build.gradle.kts
     */
    const val VERSION = "0.2.0"

    /**
     * Application name
     */
    const val APP_NAME = "ChapterVault"

    /**
     * Check if running in development mode.
     * Set CHAPTERVAULT_ENV=development to enable dev mode.
     */
    val isDevelopment: Boolean
        get() = System.getenv("CHAPTERVAULT_ENV")?.lowercase() == "development"

    /**
     * Check if running in production mode (default).
     */
    val isProduction: Boolean
        get() = !isDevelopment
}
