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
 */
data class StorageConfig(
    /** Base directory for downloads */
    val basePath: File,
    /** Storage format (CBZ or FOLDER) */
    val format: StorageFormat,
    /** Minimum free space in MB to maintain */
    val minFreeSpaceMB: Long = 500
)
