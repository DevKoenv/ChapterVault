package dev.koenv.chaptervault.storage.impl

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.storage.StorageSink
import java.io.File

/**
 * File-based storage implementation that creates folder structures with PNG files.
 * Stores images as: series-name/chapter-number/001.png, 002.png, etc.
 */
class FolderStorageSink(
    private val baseDir: File
) : StorageSink {
    
    private var currentSeriesMetadata: SeriesMetadata? = null
    private var currentChapterMetadata: ChapterMetadata? = null
    private var currentSeriesDir: File? = null
    private var currentChapterDir: File? = null
    
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
        val seriesDir = currentSeriesDir ?: return
        
        // Create chapter directory
        val safeChapterName = "Chapter ${metadata.chapterNumber}"
            .replace(Regex("[^a-zA-Z0-9 -]"), "").trim()
        currentChapterDir = File(seriesDir, safeChapterName)
        currentChapterDir?.mkdirs()
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
        file.writeBytes(bytes)
    }
    
    override suspend fun endChapter() {
        currentChapterMetadata = null
        currentChapterDir = null
    }
    
    override suspend fun endSeries() {
        currentSeriesMetadata = null
        currentSeriesDir = null
    }
}
