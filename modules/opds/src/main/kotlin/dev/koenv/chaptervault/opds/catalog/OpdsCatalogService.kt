package dev.koenv.chaptervault.opds.catalog

import dev.koenv.chaptervault.database.entity.DownloadStatus
import dev.koenv.chaptervault.database.repository.CachedChapter
import dev.koenv.chaptervault.database.repository.CachedSeries
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.opds.builder.opdsFeed
import dev.koenv.chaptervault.opds.model.*
import dev.koenv.chaptervault.opds.renderer.FeedRenderer
import dev.koenv.chaptervault.opds.renderer.FeedRendererFactory
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * OPDS Catalog Service
 *
 * Generates OPDS feeds from the database.
 * Supports multiple OPDS versions through the renderer abstraction.
 */
class OpdsCatalogService(
    private val seriesRepository: SeriesRepository,
    private val chapterRepository: ChapterRepository,
    private val storageBasePath: File,
    private val baseUrl: String = "http://localhost:8080/opds",
    private val version: OpdsVersion = OpdsVersion.V1_2,
    private val enablePse: Boolean = true  // Page Streaming Extension
) {
    private val renderer: FeedRenderer = FeedRendererFactory.create(version)

    /**
     * Content type for HTTP responses
     */
    val contentType: String get() = renderer.contentType

    /**
     * Generate the root catalog feed
     * Shows all series in the library
     */
    fun generateRootFeed(): String {
        val series = seriesRepository.findAll()
            .filter { hasDownloadedChapters(it.id) }

        val feed = opdsFeed {
            id = baseUrl
            title = "ChapterVault Library"
            updated = series.maxOfOrNull { it.updatedAt } ?: Instant.now()
            author { name = "ChapterVault" }

            // Navigation links
            link {
                href = baseUrl
                rel = LinkRel.SELF
                type = MimeTypes.OPDS_NAVIGATION
            }
            link {
                href = baseUrl
                rel = LinkRel.START
                type = MimeTypes.OPDS_NAVIGATION
            }

            // Search link (for clients that support it)
            link {
                href = "$baseUrl/search?q={searchTerms}"
                rel = LinkRel.SEARCH
                type = MimeTypes.OPDS_CATALOG
                title = "Search"
            }

            // Series entries
            series.forEach { s ->
                entry {
                    id = "$baseUrl/series/${s.id}"
                    title = s.title
                    updated = s.updatedAt

                    s.author?.let { authorName ->
                        author { name = authorName }
                    }

                    s.description?.let { desc ->
                        summary = desc.take(500)
                    }

                    content(buildSeriesDescription(s))

                    // Tags as categories
                    s.tags.forEach { tag ->
                        category(tag)
                    }

                    // Cover image link
                    s.coverUrl?.let { cover ->
                        link {
                            href = cover
                            rel = LinkRel.THUMBNAIL
                            type = MimeTypes.JPEG
                        }
                    }

                    // Subsection link to series feed
                    link {
                        href = "$baseUrl/series/${s.id}"
                        rel = LinkRel.SUBSECTION
                        type = MimeTypes.OPDS_ACQUISITION
                    }
                }
            }
        }

        return renderer.render(feed)
    }

    /**
     * Generate feed for a specific series
     * Shows all downloaded chapters
     */
    fun generateSeriesFeed(seriesId: UUID): String? {
        val series = seriesRepository.findById(seriesId) ?: return null
        val chapters = chapterRepository.findDownloaded(seriesId)
            .sortedBy { parseChapterNumber(it.chapterNumber) }

        if (chapters.isEmpty()) return null

        val feed = opdsFeed {
            id = "$baseUrl/series/$seriesId"
            title = series.title
            updated = chapters.maxOfOrNull { it.updatedAt } ?: series.updatedAt

            series.author?.let { authorName ->
                author { name = authorName }
            }

            // Navigation links
            link {
                href = "$baseUrl/series/$seriesId"
                rel = LinkRel.SELF
                type = MimeTypes.OPDS_ACQUISITION
            }
            link {
                href = baseUrl
                rel = LinkRel.START
                type = MimeTypes.OPDS_NAVIGATION
            }
            link {
                href = baseUrl
                rel = LinkRel.UP
                type = MimeTypes.OPDS_NAVIGATION
            }

            // Chapter entries
            chapters.forEach { chapter ->
                entry {
                    id = "$baseUrl/series/$seriesId/chapter/${chapter.id}"
                    title = chapter.title
                    updated = chapter.updatedAt
                    published = chapter.downloadedAt

                    series.author?.let { authorName ->
                        author { name = authorName }
                    }

                    content(buildChapterDescription(chapter, series))

                    // Acquisition link for download
                    val file = chapter.filePath?.let { File(it) }
                    val mimeType = getMimeTypeForFile(chapter.storageFormat)

                    link {
                        href = "$baseUrl/download/${chapter.id}"
                        rel = LinkRel.ACQUISITION_OPEN
                        type = mimeType
                        chapter.fileSize?.let { length = it }

                        // PSE extension for page streaming
                        chapter.pageCount?.takeIf { enablePse && it > 0 }?.let { count ->
                            pse(count = count)
                        }
                    }

                    // PSE stream link for page-by-page reading
                    chapter.pageCount?.takeIf { enablePse && it > 0 }?.let { count ->
                        pse(count)

                        link {
                            href = "$baseUrl/stream/${chapter.id}/{pageNumber}"
                            rel = LinkRel.PAGE_STREAM
                            type = MimeTypes.JPEG
                            pse(count = count)
                        }
                    }
                }
            }
        }

        return renderer.render(feed)
    }

    /**
     * Get file for download
     */
    fun getChapterFile(chapterId: UUID): File? {
        val chapter = chapterRepository.findById(chapterId) ?: return null
        if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) return null
        val filePath = chapter.filePath ?: return null

        val file = File(filePath)
        return if (file.exists()) file else null
    }

    /**
     * Get chapter info for streaming
     */
    fun getChapterInfo(chapterId: UUID): ChapterStreamInfo? {
        val chapter = chapterRepository.findById(chapterId) ?: return null
        if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) return null
        val filePath = chapter.filePath ?: return null

        return ChapterStreamInfo(
            id = chapter.id,
            title = chapter.title,
            filePath = filePath,
            pageCount = chapter.pageCount ?: 0,
            storageFormat = chapter.storageFormat ?: "cbz"
        )
    }

    /**
     * Search series by query
     */
    fun searchSeries(query: String): String {
        val allSeries = seriesRepository.findAll()
            .filter { hasDownloadedChapters(it.id) }

        val matching = allSeries.filter { series ->
            series.title.contains(query, ignoreCase = true) ||
            series.author?.contains(query, ignoreCase = true) == true ||
            series.tags.any { it.contains(query, ignoreCase = true) }
        }

        val feed = opdsFeed {
            id = "$baseUrl/search?q=$query"
            title = "Search Results: $query"
            updated = Instant.now()
            author { name = "ChapterVault" }

            link {
                href = "$baseUrl/search?q=$query"
                rel = LinkRel.SELF
                type = MimeTypes.OPDS_ACQUISITION
            }
            link {
                href = baseUrl
                rel = LinkRel.START
                type = MimeTypes.OPDS_NAVIGATION
            }

            matching.forEach { s ->
                entry {
                    id = "$baseUrl/series/${s.id}"
                    title = s.title
                    updated = s.updatedAt

                    s.author?.let { authorName ->
                        author { name = authorName }
                    }

                    s.description?.let { desc ->
                        summary = desc.take(500)
                    }

                    link {
                        href = "$baseUrl/series/${s.id}"
                        rel = LinkRel.SUBSECTION
                        type = MimeTypes.OPDS_ACQUISITION
                    }
                }
            }
        }

        return renderer.render(feed)
    }

    private fun hasDownloadedChapters(seriesId: UUID): Boolean {
        return chapterRepository.countDownloaded(seriesId) > 0
    }

    private fun buildSeriesDescription(series: CachedSeries): String {
        val chapterCount = chapterRepository.countDownloaded(series.id)
        return buildString {
            append("Series: ${series.title}")
            series.author?.let { append(" by $it") }
            append(" - $chapterCount chapter(s) available")
            if (series.tags.isNotEmpty()) {
                append(" | Tags: ${series.tags.joinToString(", ")}")
            }
        }
    }

    private fun buildChapterDescription(chapter: CachedChapter, series: CachedSeries): String {
        return buildString {
            append("Chapter ${chapter.chapterNumber}: ${chapter.title}")
            chapter.pageCount?.let { append(" ($it pages)") }
            chapter.fileSize?.let { append(" - ${formatFileSize(it)}") }
        }
    }

    private fun parseChapterNumber(chapterNumber: String): Double {
        return chapterNumber.toDoubleOrNull() ?: 0.0
    }

    private fun getMimeTypeForFile(storageFormat: String?): String {
        return when (storageFormat?.lowercase()) {
            "cbz", "zip" -> MimeTypes.CBZ
            "cbr", "rar" -> MimeTypes.CBR
            "epub" -> MimeTypes.EPUB
            "pdf" -> MimeTypes.PDF
            "folder" -> MimeTypes.CBZ  // Folders served as CBZ
            else -> MimeTypes.CBZ
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

/**
 * Chapter info for streaming pages
 */
data class ChapterStreamInfo(
    val id: UUID,
    val title: String,
    val filePath: String,
    val pageCount: Int,
    val storageFormat: String
)
