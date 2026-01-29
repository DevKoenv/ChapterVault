package dev.koenv.chaptervault.core.config

import java.io.File

/**
 * Storage format options.
 */
enum class StorageFormat {
    /** Comic Book Archive (ZIP containing images) */
    CBZ,
    /** Individual image files in folders */
    FOLDER
}

/**
 * Configuration for storage settings.
 * Can be loaded from environment variables.
 */
data class StorageConfig(
    /** Base directory for downloads */
    val basePath: File,
    /** Storage format (CBZ or FOLDER) */
    val format: StorageFormat,
    /** Minimum free space in MB to maintain */
    val minFreeSpaceMB: Long = 100
) {
    companion object {
        /**
         * Load storage configuration from environment variables.
         *
         * Environment variables:
         * - CHAPTERVAULT_STORAGE_PATH: Base directory for downloads (default: ~/ChapterVault/downloads)
         * - CHAPTERVAULT_STORAGE_FORMAT: Format to use - CBZ or FOLDER (default: CBZ)
         * - CHAPTERVAULT_MIN_FREE_SPACE_MB: Minimum free space to maintain in MB (default: 100)
         */
        fun fromEnvironment(): StorageConfig {
            val basePath = System.getenv("CHAPTERVAULT_STORAGE_PATH")
                ?.let { File(it) }
                ?: File(System.getProperty("user.home"), "ChapterVault/downloads")

            val format = System.getenv("CHAPTERVAULT_STORAGE_FORMAT")
                ?.uppercase()
                ?.let {
                    try {
                        StorageFormat.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                ?: StorageFormat.CBZ

            val minFreeSpace = System.getenv("CHAPTERVAULT_MIN_FREE_SPACE_MB")
                ?.toLongOrNull()
                ?: 100

            return StorageConfig(
                basePath = basePath,
                format = format,
                minFreeSpaceMB = minFreeSpace
            )
        }
    }
}
