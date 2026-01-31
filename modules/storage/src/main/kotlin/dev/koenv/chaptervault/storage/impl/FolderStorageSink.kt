package dev.koenv.chaptervault.storage.impl

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.storage.StorageSink
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * File-based storage implementation that creates folder structures with image files.
 * Stores images as: series-name/chapter-number/001.png, 002.png, etc.
 *
 * Features:
 * - Simple folder structure
 * - Cleanup on failure
 * - Disk space validation
 *
 * @param baseDir Base directory for storing files
 * @param minFreeSpaceMB Minimum free space to maintain (from ConfigurationService)
 */
class FolderStorageSink(
    private val baseDir: File,
    private val minFreeSpaceMB: Long
) : StorageSink {

    private var currentSeriesMetadata: SeriesMetadata? = null
    private var currentChapterMetadata: ChapterMetadata? = null
    private var currentSeriesDir: File? = null
    private var currentChapterDir: File? = null
    private var lastWrittenPath: String? = null
    private var lastWrittenSize: Long? = null
    private var currentChapterSize: Long = 0

    init {
        baseDir.mkdirs()
    }

    override suspend fun beginSeries(metadata: SeriesMetadata) {
        currentSeriesMetadata = metadata
        // Create a safe directory name from series title
        val safeDirName = sanitizeFilename(metadata.title)
        currentSeriesDir = File(baseDir, safeDirName)
        currentSeriesDir?.mkdirs()
        logger.debug { "Beginning series: ${metadata.title} in ${currentSeriesDir?.absolutePath}" }
    }

    override suspend fun beginChapter(metadata: ChapterMetadata) {
        currentChapterMetadata = metadata
        val seriesDir = currentSeriesDir ?: return

        // Create chapter directory
        val safeChapterName = sanitizeFilename("Chapter ${metadata.chapterNumber}")
        currentChapterDir = File(seriesDir, safeChapterName)
        currentChapterDir?.mkdirs()
        currentChapterSize = 0
        logger.debug { "Beginning chapter: ${metadata.chapterNumber} in ${currentChapterDir?.absolutePath}" }
    }

    override suspend fun writePage(pageIndex: Int, bytes: ByteArray, mimeType: String) {
        val chapterDir = currentChapterDir ?: return

        val extension = when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }

        // Write file with padded index
        val fileName = String.format("%03d.%s", pageIndex + 1, extension)
        val file = File(chapterDir, fileName)

        try {
            file.writeBytes(bytes)
            currentChapterSize += bytes.size
            logger.trace { "Wrote page $pageIndex (${bytes.size} bytes)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write page $pageIndex: ${e.message}" }
            throw StorageException("Failed to write page $pageIndex: ${e.message}", e)
        }
    }

    override suspend fun endChapter() {
        val chapterDir = currentChapterDir
        if (chapterDir != null) {
            lastWrittenPath = chapterDir.absolutePath
            lastWrittenSize = currentChapterSize
            logger.info { "Completed chapter in: ${chapterDir.name} ($currentChapterSize bytes)" }
        }

        currentChapterMetadata = null
        currentChapterDir = null
        currentChapterSize = 0
    }

    override suspend fun endSeries() {
        logger.debug { "Ending series: ${currentSeriesMetadata?.title}" }
        currentSeriesMetadata = null
        currentSeriesDir = null
    }

    override suspend fun cleanup() {
        logger.info { "Cleaning up partial files" }

        // Delete current chapter directory if it exists
        currentChapterDir?.let { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
                logger.debug { "Deleted partial chapter directory: ${dir.absolutePath}" }
            }
        }

        // Clear current state
        currentChapterMetadata = null
        currentChapterDir = null
        currentChapterSize = 0
    }

    override suspend fun getLastWrittenPath(): String? = lastWrittenPath

    override suspend fun getLastWrittenSize(): Long? = lastWrittenSize

    override suspend fun getAvailableSpace(): Long {
        return baseDir.usableSpace
    }

    override fun validateSpace(estimatedBytes: Long): Boolean {
        val available = baseDir.usableSpace
        val minBuffer = minFreeSpaceMB * 1024 * 1024
        val hasSpace = available > estimatedBytes + minBuffer

        if (!hasSpace) {
            logger.warn {
                "Insufficient disk space. Available: ${available / (1024 * 1024)} MB, " +
                "Required: ${(estimatedBytes + minBuffer) / (1024 * 1024)} MB (including $minFreeSpaceMB MB buffer)"
            }
        }

        return hasSpace
    }

    /**
     * Sanitize a string for use as a filename.
     * Removes invalid characters and limits length.
     */
    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "") // Remove invalid chars
            .replace(Regex("\\s+"), " ")           // Normalize whitespace
            .trim()
            .take(200)                              // Limit length
            .ifEmpty { "unknown" }
    }
}
