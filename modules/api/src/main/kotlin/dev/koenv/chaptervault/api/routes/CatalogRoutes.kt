package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.catalog.*
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.repository.CachedChapter
import dev.koenv.chaptervault.core.repository.CachedSeries
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.DownloadStatus
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Catalog routes - search and browse all known content.
 */
fun Route.catalogRoutes(
    orchestrator: Orchestrator,
    connectorRegistry: ConnectorRegistry,
    seriesRepository: SeriesRepositoryPort,
    chapterRepository: ChapterRepositoryPort
) {
    route("/api/v1/catalog") {

        /**
         * GET /api/v1/catalog/connectors
         * List all available connectors.
         */
        get("/connectors") {
            val connectors = connectorRegistry.getAllConnectors().map { connector ->
                ConnectorDto(
                    id = connector.config.id,
                    name = connector.config.name,
                    version = connector.config.version,
                    features = ConnectorFeaturesDto(
                        search = connector.config.features.supportsSearch,
                        download = connector.config.features.supportsBatchDownload,
                        pageCount = connector.config.features.supportsPageCount,
                        requiresAuth = connector.config.features.requiresAuth
                    ),
                    priority = connector.config.priority
                )
            }
            call.respond(HttpStatusCode.OK, ConnectorsListResponse(connectors = connectors))
        }

        /**
         * POST /api/v1/catalog/lookup
         * Lookup series by URL or search by query.
         *
         * Request body fields:
         * - `url`: Direct URL to a series page (connector auto-detected)
         * - `query` + `source`: Search term with connector ID
         *
         * At least one of `url` or `query` must be provided.
         * When using `query`, the `source` parameter is required.
         *
         * Returns [CatalogSeriesDetailResponse] for URL lookups.
         * Returns [CatalogLookupResponse] for search queries.
         */
        post("/lookup") {
            val request = try {
                call.receive<CatalogLookupRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Request",
                        status = 400,
                        detail = "Invalid request body: ${e.message}",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            val url = request.url?.trim()?.takeIf { it.isNotBlank() }
            val query = request.query?.trim()?.takeIf { it.isNotBlank() }

            if (url == null && query == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Missing Parameter",
                        status = 400,
                        detail = "At least one of 'url' or 'query' must be provided",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            if (url != null) {
                // URL lookup - fetch series metadata directly
                try {
                    var series = seriesRepository.findByUrl(url)

                    if (series == null) {
                        val metadata = orchestrator.fetchSeriesMetadata(url)
                        series = seriesRepository.save(metadata, null)
                    } else if (series.metadataFetchedAt == null) {
                        val metadata = orchestrator.fetchSeriesMetadata(url)
                        series = seriesRepository.save(metadata, series.language)
                    }

                    val chapters = chapterRepository.findBySeriesId(series.id)
                    val totalChapters = chapterRepository.countBySeriesId(series.id).toInt()
                    val downloadedChapters = chapterRepository.countDownloaded(series.id).toInt()

                    call.respond(
                        HttpStatusCode.OK,
                        CatalogSeriesDetailResponse(
                            id = series.id.toString(),
                            sourceUrl = series.sourceUrl,
                            title = series.title,
                            description = series.description,
                            author = series.author,
                            coverUrl = series.coverUrl,
                            tags = series.tags,
                            status = series.status.name,
                            totalChapters = totalChapters,
                            downloadedChapters = downloadedChapters,
                            inLibrary = series.inLibrary,
                            addedToLibraryAt = series.addedToLibraryAt?.toString(),
                            chapters = chapters.map { it.toCatalogChapterDto() }
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProblemDetail(
                            type = ErrorTypes.VALIDATION,
                            title = "Unsupported URL",
                            status = 400,
                            detail = e.message ?: "No connector supports this URL",
                            instance = call.request.local.uri
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ProblemDetail(
                            type = ErrorTypes.INTERNAL_ERROR,
                            title = "Lookup Failed",
                            status = 500,
                            detail = e.message ?: "Unknown error",
                            instance = call.request.local.uri
                        )
                    )
                }
            } else {
                // Search query - requires source parameter
                val source = request.source?.trim()?.takeIf { it.isNotBlank() }
                if (source == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProblemDetail(
                            type = ErrorTypes.VALIDATION,
                            title = "Source Required",
                            status = 400,
                            detail = "Search queries require a 'source' parameter to specify which connector to search",
                            instance = call.request.local.uri
                        )
                    )
                    return@post
                }

                if (connectorRegistry.findById(source) == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProblemDetail(
                            type = ErrorTypes.VALIDATION,
                            title = "Invalid Source",
                            status = 400,
                            detail = "Unknown connector: $source",
                            instance = call.request.local.uri
                        )
                    )
                    return@post
                }

                try {
                    val searchResults = orchestrator.searchSeries(query!!, source)
                    val cachedSeries = seriesRepository.saveAllFromSearch(searchResults)

                    val results = cachedSeries.map { series ->
                        series.toCatalogDto(chapterRepository)
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        CatalogLookupResponse(
                            series = results,
                            source = source
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ProblemDetail(
                            type = ErrorTypes.INTERNAL_ERROR,
                            title = "Search Failed",
                            status = 500,
                            detail = e.message ?: "Unknown error",
                            instance = call.request.local.uri
                        )
                    )
                }
            }
        }

        /**
         * GET /api/v1/catalog/series/{seriesId}
         * Get series detail with all chapters.
         * Auto-fetches full metadata from source if not yet fetched.
         */
        get("/series/{seriesId}") {
            val seriesIdParam = call.parameters["seriesId"]

            val seriesId = try {
                UUID.fromString(seriesIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Series ID",
                        status = 400,
                        detail = "Invalid UUID format: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            var series = seriesRepository.findById(seriesId)
            if (series == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = "No series found with ID: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            // Auto-fetch full metadata if not yet fetched
            if (series.metadataFetchedAt == null) {
                try {
                    val freshMetadata = orchestrator.fetchSeriesMetadata(series.sourceUrl)
                    series = seriesRepository.save(freshMetadata, series.language)
                } catch (_: Exception) {
                    // Fetch failed - continue with cached search data
                }
            }

            val chapters = chapterRepository.findBySeriesId(seriesId)
            val totalChapters = chapterRepository.countBySeriesId(seriesId).toInt()
            val downloadedChapters = chapterRepository.countDownloaded(seriesId).toInt()

            call.respond(
                HttpStatusCode.OK,
                CatalogSeriesDetailResponse(
                    id = series.id.toString(),
                    sourceUrl = series.sourceUrl,
                    title = series.title,
                    description = series.description,
                    author = series.author,
                    coverUrl = series.coverUrl,
                    tags = series.tags,
                    status = series.status.name,
                    totalChapters = totalChapters,
                    downloadedChapters = downloadedChapters,
                    inLibrary = series.inLibrary,
                    addedToLibraryAt = series.addedToLibraryAt?.toString(),
                    chapters = chapters.map { it.toCatalogChapterDto() }
                )
            )
        }

        /**
         * POST /api/v1/catalog/series/{seriesId}/refresh
         * Force refresh metadata for a series from the source.
         */
        post("/series/{seriesId}/refresh") {
            val seriesIdParam = call.parameters["seriesId"]

            val seriesId = try {
                UUID.fromString(seriesIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Series ID",
                        status = 400,
                        detail = "Invalid UUID format: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            val existingSeries = seriesRepository.findById(seriesId)
            if (existingSeries == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = "No series found with ID: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                // Fetch fresh metadata from the source
                val freshMetadata = orchestrator.fetchSeriesMetadata(existingSeries.sourceUrl)

                // Save the updated metadata (this will update metadataFetchedAt)
                val updatedSeries = seriesRepository.save(freshMetadata, existingSeries.language)

                val chapters = chapterRepository.findBySeriesId(seriesId)
                val totalChapters = chapterRepository.countBySeriesId(seriesId).toInt()
                val downloadedChapters = chapterRepository.countDownloaded(seriesId).toInt()

                call.respond(
                    HttpStatusCode.OK,
                    CatalogSeriesDetailResponse(
                        id = updatedSeries.id.toString(),
                        sourceUrl = updatedSeries.sourceUrl,
                        title = updatedSeries.title,
                        description = updatedSeries.description,
                        author = updatedSeries.author,
                        coverUrl = updatedSeries.coverUrl,
                        tags = updatedSeries.tags,
                        status = updatedSeries.status.name,
                        totalChapters = totalChapters,
                        downloadedChapters = downloadedChapters,
                        inLibrary = updatedSeries.inLibrary,
                        addedToLibraryAt = updatedSeries.addedToLibraryAt?.toString(),
                        chapters = chapters.map { it.toCatalogChapterDto() }
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Metadata Refresh Failed",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }
    }
}

private fun CachedSeries.toCatalogDto(chapterRepository: ChapterRepositoryPort): CatalogSeriesDto {
    val totalChapters = chapterRepository.countBySeriesId(id).toInt()
    val downloadedChapters = chapterRepository.countDownloaded(id).toInt()
    return CatalogSeriesDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        description = description,
        author = author,
        coverUrl = coverUrl,
        tags = tags,
        status = status.name,
        totalChapters = totalChapters,
        downloadedChapters = downloadedChapters,
        inLibrary = inLibrary,
        addedToLibraryAt = addedToLibraryAt?.toString()
    )
}

private fun CachedChapter.toCatalogChapterDto(): CatalogChapterDto {
    return CatalogChapterDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        chapterNumber = chapterNumber,
        publishDate = publishDate,
        pageCount = pageCount,
        downloaded = downloadStatus == DownloadStatus.DOWNLOADED,
        downloadStatus = downloadStatus.name
    )
}
