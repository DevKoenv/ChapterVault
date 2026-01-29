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

    /**
     * Cleanup any partial files from the current operation.
     * Call this when an error occurs to remove incomplete downloads.
     */
    suspend fun cleanup()

    /**
     * Get the path of the last successfully written chapter file.
     * Returns null if no chapter has been written yet.
     */
    suspend fun getLastWrittenPath(): String?

    /**
     * Get the file size of the last successfully written chapter.
     * Returns null if no chapter has been written yet.
     */
    suspend fun getLastWrittenSize(): Long?

    /**
     * Get the available space on the storage device in bytes.
     */
    suspend fun getAvailableSpace(): Long

    /**
     * Check if there's enough space for the estimated download.
     * @param estimatedBytes Estimated size of the download in bytes
     * @return true if there's enough space, false otherwise
     */
    fun validateSpace(estimatedBytes: Long): Boolean
}
