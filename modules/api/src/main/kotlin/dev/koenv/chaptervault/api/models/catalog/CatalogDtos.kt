package dev.koenv.chaptervault.api.models.catalog

import dev.koenv.chaptervault.api.models.Pagination
import kotlinx.serialization.Serializable

/**
 * Response for listing series in the catalog.
 */
@Serializable
data class CatalogSeriesListResponse(
    val series: List<CatalogSeriesDto>,
    val pagination: Pagination
)

/**
 * Series summary for catalog list view.
 * Note: id is null for external series not yet added to library
 */
@Serializable
data class CatalogSeriesDto(
    val id: String?,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val download: DownloadSummaryDto
)

/**
 * Detailed series response with chapters.
 */
@Serializable
data class CatalogSeriesDetailResponse(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val download: DownloadSummaryDto,
    val chapters: List<CatalogChapterDto>
)

/**
 * Chapter info for catalog view.
 */
@Serializable
data class CatalogChapterDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val chapterNumber: String,
    val publishDate: String?,
    val pageCount: Int?,
    val downloaded: Boolean,
    val downloadStatus: String
)

/**
 * Download state summary for a series.
 */
@Serializable
data class DownloadSummaryDto(
    val totalChapters: Int,
    val downloadedChapters: Int,
    val hasDownloads: Boolean
)

/**
 * Response for listing available connectors.
 */
@Serializable
data class ConnectorsListResponse(
    val connectors: List<ConnectorDto>
)

/**
 * Connector information for API consumers.
 */
@Serializable
data class ConnectorDto(
    val id: String,
    val name: String,
    val version: String,
    val features: ConnectorFeaturesDto,
    val priority: Int
)

/**
 * Connector feature flags.
 */
@Serializable
data class ConnectorFeaturesDto(
    val search: Boolean,
    val download: Boolean,
    val pageCount: Boolean,
    val requiresAuth: Boolean
)
