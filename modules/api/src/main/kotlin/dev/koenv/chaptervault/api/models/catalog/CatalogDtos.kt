package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

/**
 * Series summary for catalog/lookup results.
 */
@Serializable
data class CatalogSeriesDto(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val tags: List<String>,
    val status: String,
    val download: DownloadSummaryDto,
    val inLibrary: Boolean
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
    val chapters: List<CatalogChapterDto>,
    val inLibrary: Boolean
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

/**
 * Request body for looking up series by URL or search query.
 * At least one of [url] or [query] must be provided.
 *
 * @property url Direct URL to a series page. Connector is auto-detected from the URL.
 * @property query Search term to find series. Requires [source] to be specified.
 * @property source Connector ID to search (e.g., "asura-scans"). Required when using [query].
 */
@Serializable
data class CatalogLookupRequest(
    val url: String? = null,
    val query: String? = null,
    val source: String? = null
)

/**
 * Response for lookup operations that return multiple results.
 */
@Serializable
data class CatalogLookupResponse(
    val series: List<CatalogSeriesDto>,
    val source: String
)
