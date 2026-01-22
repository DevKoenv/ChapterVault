package dev.koenv.chaptervault.storage.impl

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.storage.StorageSink
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-based storage implementation that creates CBZ files.
 * CBZ is a comic book archive format (ZIP containing images).
 */
class FileStorageSink(
    private val baseDir: File
) : StorageSink {
    
    private var currentSeriesMetadata: SeriesMetadata? = null
    private var currentChapterMetadata: ChapterMetadata? = null
    private var currentSeriesDir: File? = null
    private var currentChapterPages: MutableList<PageData> = mutableListOf()
    
    init {
        baseDir.mkdirs()
    }
    
    override suspend fun beginSeries(metadata: SeriesMetadata) {
        currentSeriesMetadata = metadata
        // Create a safe directory name from series title
        val safeDirName = metadata.title.replace(Regex("[^a-zA-Z0-9 -]"), "").trim()
        currentSeriesDir = File(baseDir, safeDirName)
        currentSeriesDir?.mkdirs()
    }
    
    override suspend fun beginChapter(metadata: ChapterMetadata) {
        currentChapterMetadata = metadata
        currentChapterPages.clear()
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
    }
    
    override suspend fun endChapter() {
        val chapterMetadata = currentChapterMetadata ?: return
        val seriesMetadata = currentSeriesMetadata ?: return
        val seriesDir = currentSeriesDir ?: return
        
        // Create CBZ file for the chapter
        val safeChapterName = "${chapterMetadata.chapterNumber} - ${chapterMetadata.title}"
            .replace(Regex("[^a-zA-Z0-9 -]"), "").trim()
        val cbzFile = File(seriesDir, "$safeChapterName.cbz")
        
        // Write pages to CBZ (ZIP) with ComicInfo.xml
        ZipOutputStream(cbzFile.outputStream()).use { zip ->
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
        
        currentChapterPages.clear()
        currentChapterMetadata = null
    }
    
    override suspend fun endSeries() {
        currentSeriesMetadata = null
        currentSeriesDir = null
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
                appendLine("  <Year>${publishDate.take(4)}</Year>")
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
