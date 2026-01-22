package dev.koenv.chaptervault.opds.catalog

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * OPDS v1.2 Catalog Generator
 * Generates Atom-based OPDS feeds for browsing downloaded content
 */
class OpdsCatalogGenerator(
    private val storageDir: File,
    private val baseUrl: String = "http://localhost:8080/opds"
) {
    
    /**
     * Generate root catalog feed
     */
    fun generateRootFeed(): String {
        val series = getDownloadedSeries()
        
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<feed xmlns="http://www.w3.org/2005/Atom">""")
            appendLine("  <id>$baseUrl</id>")
            appendLine("  <title>ChapterVault Library</title>")
            appendLine("  <updated>${currentTimestamp()}</updated>")
            appendLine("  <author><name>ChapterVault</name></author>")
            appendLine("""  <link rel="self" href="$baseUrl" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>""")
            appendLine("""  <link rel="start" href="$baseUrl" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>""")
            
            series.forEach { seriesDir ->
                val seriesName = seriesDir.name
                val seriesId = seriesName.hashCode().toString()
                
                appendLine("  <entry>")
                appendLine("    <id>$baseUrl/series/$seriesId</id>")
                appendLine("    <title>$seriesName</title>")
                appendLine("    <updated>${currentTimestamp()}</updated>")
                appendLine("""    <link rel="subsection" href="$baseUrl/series/$seriesId" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>""")
                appendLine("    <content type=\"text\">Series: $seriesName</content>")
                appendLine("  </entry>")
            }
            
            appendLine("</feed>")
        }
    }
    
    /**
     * Generate series feed (list of chapters/books)
     */
    fun generateSeriesFeed(seriesId: String): String? {
        val series = getDownloadedSeries()
        val seriesDir = series.firstOrNull { it.name.hashCode().toString() == seriesId }
            ?: return null
        
        val chapters = seriesDir.listFiles()?.filter { it.extension == "cbz" }?.sortedBy { it.name }
            ?: emptyList()
        
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<feed xmlns="http://www.w3.org/2005/Atom">""")
            appendLine("  <id>$baseUrl/series/$seriesId</id>")
            appendLine("  <title>${seriesDir.name}</title>")
            appendLine("  <updated>${currentTimestamp()}</updated>")
            appendLine("  <author><name>ChapterVault</name></author>")
            appendLine("""  <link rel="self" href="$baseUrl/series/$seriesId" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>""")
            appendLine("""  <link rel="start" href="$baseUrl" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>""")
            appendLine("""  <link rel="up" href="$baseUrl" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>""")
            
            chapters.forEachIndexed { index, chapterFile ->
                val chapterName = chapterFile.nameWithoutExtension
                val chapterId = "${seriesId}_${index}"
                val fileSize = chapterFile.length()
                
                appendLine("  <entry>")
                appendLine("    <id>$baseUrl/series/$seriesId/chapter/$chapterId</id>")
                appendLine("    <title>$chapterName</title>")
                appendLine("    <updated>${getFileTimestamp(chapterFile)}</updated>")
                appendLine("""    <link rel="http://opds-spec.org/acquisition" href="$baseUrl/download/$seriesId/$chapterId" type="application/x-cbz" length="$fileSize"/>""")
                appendLine("    <content type=\"text\">$chapterName</content>")
                appendLine("  </entry>")
            }
            
            appendLine("</feed>")
        }
    }
    
    /**
     * Download a chapter file
     */
    fun getChapterFile(seriesId: String, chapterId: String): File? {
        val series = getDownloadedSeries()
        val seriesDir = series.firstOrNull { it.name.hashCode().toString() == seriesId }
            ?: return null
        
        val chapters = seriesDir.listFiles()?.filter { it.extension == "cbz" }?.sortedBy { it.name }
            ?: return null
        
        val chapterIndex = chapterId.substringAfterLast("_").toIntOrNull() ?: return null
        return chapters.getOrNull(chapterIndex)
    }
    
    private fun getDownloadedSeries(): List<File> {
        if (!storageDir.exists()) {
            return emptyList()
        }
        return storageDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }
    
    private fun currentTimestamp(): String {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
    }
    
    private fun getFileTimestamp(file: File): String {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
    }
}
