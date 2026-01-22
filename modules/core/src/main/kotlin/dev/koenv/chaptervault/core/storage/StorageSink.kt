package dev.koenv.chaptervault.core.storage

import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata

/**
 * Storage sink receives bytes from connectors and writes files.
 * Connectors do NOT write files directly; they pass binary data to storage.
 * 
 * Storage is deterministic and synchronous.
 */
interface StorageSink {
    /**
     * Begin a new series. Storage may create a directory structure.
     */
    suspend fun beginSeries(metadata: SeriesMetadata)
    
    /**
     * Begin a new chapter within the current series.
     */
    suspend fun beginChapter(metadata: ChapterMetadata)
    
    /**
     * Write a page to the current chapter.
     * @param pageIndex Zero-based index of the page
     * @param bytes The binary data of the page
     * @param mimeType MIME type (e.g., "image/jpeg", "image/png")
     */
    suspend fun writePage(pageIndex: Int, bytes: ByteArray, mimeType: String)
    
    /**
     * Finish the current chapter. Storage may finalize the chapter (e.g., create CBZ).
     */
    suspend fun endChapter()
    
    /**
     * Finish the current series. Storage may perform final cleanup.
     */
    suspend fun endSeries()
}
