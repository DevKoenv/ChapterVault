package dev.koenv.chaptervault.storage.impl

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.storage.StorageSink
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

/**
 * File-based storage implementation that creates CBZ files.
 * CBZ is a comic book archive format (ZIP containing images).
 *
 * Features:
 * - Atomic writes using temp files
 * - Cleanup on failure
 * - Disk space validation
 *
 * @param baseDir Base directory for storing files
 * @param minFreeSpaceMB Minimum free space to maintain (from ConfigurationService)
 */
class FileStorageSink(
    private val baseDir: File,
    private val minFreeSpaceMB: Long
) : StorageSink {

    private var currentSeriesMetadata: SeriesMetadata? = null
    private var currentChapterMetadata: ChapterMetadata? = null
    private var currentSeriesDir: File? = null
    private var currentChapterPages: MutableList<PageData> = mutableListOf()
    private var currentTempFile: File? = null
    private var lastWrittenPath: String? = null
    private var lastWrittenSize: Long? = null

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
        currentChapterPages.clear()
        currentTempFile = null
        logger.debug { "Beginning chapter: ${metadata.chapterNumber} - ${metadata.title}" }
    }

    override suspend fun writePage(pageIndex: Int, bytes: ByteArray, mimeType: String) {
        val extension = when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        currentChapterPages.add(PageData(pageIndex, bytes, extension))
        logger.trace { "Wrote page $pageIndex (${bytes.size} bytes)" }
    }

    override suspend fun endChapter() {
        val chapterMetadata = currentChapterMetadata ?: return
        val seriesMetadata = currentSeriesMetadata ?: return
        val seriesDir = currentSeriesDir ?: return

        if (currentChapterPages.isEmpty()) {
            logger.warn { "No pages to write for chapter ${chapterMetadata.chapterNumber}" }
            return
        }

        // Create safe filename
        val safeChapterName = sanitizeFilename("${chapterMetadata.chapterNumber} - ${chapterMetadata.title}")
        val finalFile = File(seriesDir, "$safeChapterName.cbz")
        val tempFile = File(seriesDir, "$safeChapterName.cbz.tmp")
        currentTempFile = tempFile

        try {
            // Write to temp file first
            ZipOutputStream(tempFile.outputStream().buffered()).use { zip ->
                // Add ComicInfo.xml first
                val comicInfo = generateComicInfo(seriesMetadata, chapterMetadata, currentChapterPages.size)
                zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                zip.write(comicInfo.toByteArray())
                zip.closeEntry()

                // Add pages
                currentChapterPages.sortedBy { it.index }.forEach { page ->
                    val entryName = String.format("%03d.%s", page.index + 1, page.extension)
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(page.bytes)
                    zip.closeEntry()
                }
            }

            // Atomic move to final location
            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!tempFile.renameTo(finalFile)) {
                // Fallback: copy and delete if rename fails (different filesystem)
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            lastWrittenPath = finalFile.absolutePath
            lastWrittenSize = finalFile.length()

            logger.info { "Created CBZ: ${finalFile.name} (${currentChapterPages.size} pages, $lastWrittenSize bytes)" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to create CBZ: ${e.message}" }
            // Cleanup temp file on failure
            tempFile.delete()
            throw StorageException("Failed to write chapter: ${e.message}", e)
        } finally {
            currentChapterPages.clear()
            currentChapterMetadata = null
            currentTempFile = null
        }
    }

    override suspend fun endSeries() {
        logger.debug { "Ending series: ${currentSeriesMetadata?.title}" }
        currentSeriesMetadata = null
        currentSeriesDir = null
    }

    override suspend fun cleanup() {
        logger.info { "Cleaning up partial files" }

        // Delete current temp file if exists
        currentTempFile?.let { temp ->
            if (temp.exists()) {
                temp.delete()
                logger.debug { "Deleted temp file: ${temp.absolutePath}" }
            }
        }

        // Clear current state
        currentChapterPages.clear()
        currentChapterMetadata = null
        currentTempFile = null
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

    /**
     * Generate ComicInfo.xml for CBZ metadata
     * Based on ComicRack specification
     */
    private fun generateComicInfo(
        series: SeriesMetadata,
        chapter: ChapterMetadata,
        pageCount: Int
    ): String {
        return buildString {
            appendLine("""<?xml version="1.0"?>""")
            appendLine("""<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">""")
            appendLine("  <Title>${xmlEscape(chapter.title)}</Title>")
            appendLine("  <Series>${xmlEscape(series.title)}</Series>")
            appendLine("  <Number>${xmlEscape(chapter.chapterNumber)}</Number>")
            series.author?.let { author ->
                appendLine("  <Writer>${xmlEscape(author)}</Writer>")
            }
            series.description?.let { description ->
                appendLine("  <Summary>${xmlEscape(description)}</Summary>")
            }
            appendLine("  <PageCount>$pageCount</PageCount>")
            chapter.publishDate?.let { publishDate ->
                if (publishDate.length >= 4) {
                    appendLine("  <Year>${publishDate.take(4)}</Year>")
                }
            }
            appendLine("</ComicInfo>")
        }
    }

    /**
     * Escape XML special characters
     */
    private fun xmlEscape(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private data class PageData(
        val index: Int,
        val bytes: ByteArray,
        val extension: String
    )
}

/**
 * Exception for storage-related errors.
 */
class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
