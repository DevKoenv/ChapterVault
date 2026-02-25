package dev.koenv.chaptervault.api.models.catalog

import kotlinx.serialization.Serializable

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
