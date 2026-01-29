package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.Pagination
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.catalog.*
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.database.entity.DownloadStatus
import dev.koenv.chaptervault.database.repository.CachedChapter
import dev.koenv.chaptervault.database.repository.CachedSeries
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Catalog routes - search and browse all known content.
 */
fun Route.catalogRoutes(
    orchestrator: Orchestrator,
    connectorRegistry: ConnectorRegistry,
    seriesRepository: SeriesRepository,
    chapterRepository: ChapterRepository
) {
    route("/api/v1/catalog") {

        /**
         * GET /api/v1/catalog/connectors
         * List all available connectors.
         */
        get("/connectors") {
            val connectors = connectorRegistry.getAllConnectors().map { connector ->
                ConnectorDto(
                    id = connector.config.name.lowercase().replace(" ", "-"),
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
         * GET /api/v1/catalog/series
         * List/search all series (both downloaded and external).
         * Query params: q (search), downloaded (filter), source (connector name), offset, limit
         */
        get("/series") {
            val query = call.request.queryParameters["q"]
            val downloadedFilter = call.request.queryParameters["downloaded"]?.toBooleanStrictOrNull()
            val source = call.request.queryParameters["source"]
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

            // Validate source parameter if provided
            if (source != null && connectorRegistry.findByName(source) == null) {
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
                return@get
            }

            try {
                val results = mutableListOf<CatalogSeriesDto>()
                val seenUrls = mutableSetOf<String>()

                // Include library series (unless searching a specific external source)
                if (source == null) {
                    val librarySeries = seriesRepository.findAll()
                    librarySeries.forEach { series ->
                        val matchesQuery = query.isNullOrBlank() ||
                            series.title.contains(query, ignoreCase = true)

                        if (matchesQuery) {
                            val downloadSummary = series.toDownloadSummary(chapterRepository)
                            val matchesDownloadFilter = when (downloadedFilter) {
                                true -> downloadSummary.hasDownloads
                                false -> !downloadSummary.hasDownloads
                                null -> true
                            }

                            if (matchesDownloadFilter) {
                                results.add(series.toCatalogDto(downloadSummary))
                                seenUrls.add(series.sourceUrl)
                            }
                        }
                    }
                }

                // Search external connectors if query provided and not filtered to downloaded only
                if (!query.isNullOrBlank() && downloadedFilter != true) {
                    val externalResults = orchestrator.searchSeries(query, source)
                    externalResults.forEach { result ->
                        if (result.url !in seenUrls) {
                            // Check if already in library
                            val cached = seriesRepository.findByUrl(result.url)
                            if (cached != null) {
                                val downloadSummary = cached.toDownloadSummary(chapterRepository)
                                results.add(cached.toCatalogDto(downloadSummary))
                            } else {
                                // Not in library yet - create DTO with null ID
                                results.add(
                                    CatalogSeriesDto(
                                        id = null,
                                        sourceUrl = result.url,
                                        title = result.title,
                                        description = result.description,
                                        author = null,
                                        coverUrl = result.coverUrl,
                                        tags = emptyList(),
                                        status = "UNKNOWN",
                                        download = DownloadSummaryDto(
                                            totalChapters = 0,
                                            downloadedChapters = 0,
                                            hasDownloads = false
                                        )
                                    )
                                )
                            }
                            seenUrls.add(result.url)
                        }
                    }
                }

                val total = results.size.toLong()
                val paginatedResults = results.drop(offset).take(limit)

                call.respond(
                    HttpStatusCode.OK,
                    CatalogSeriesListResponse(
                        series = paginatedResults,
                        pagination = Pagination(
                            offset = offset,
                            limit = limit,
                            total = total,
                            hasMore = offset + paginatedResults.size < total
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Catalog Search Failed",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * GET /api/v1/catalog/series/{seriesId}
         * Get series detail with all chapters.
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

            val series = seriesRepository.findById(seriesId)
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

            val chapters = chapterRepository.findBySeriesId(seriesId)
            val downloadSummary = series.toDownloadSummary(chapterRepository)

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
                    download = downloadSummary,
                    chapters = chapters.map { it.toCatalogChapterDto() }
                )
            )
        }
    }
}

private fun CachedSeries.toDownloadSummary(chapterRepository: ChapterRepository): DownloadSummaryDto {
    val totalChapters = chapterRepository.countBySeriesId(id).toInt()
    val downloadedChapters = chapterRepository.countDownloaded(id).toInt()
    return DownloadSummaryDto(
        totalChapters = totalChapters,
        downloadedChapters = downloadedChapters,
        hasDownloads = downloadedChapters > 0
    )
}

private fun CachedSeries.toCatalogDto(downloadSummary: DownloadSummaryDto): CatalogSeriesDto {
    return CatalogSeriesDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        description = description,
        author = author,
        coverUrl = coverUrl,
        tags = tags,
        status = status.name,
        download = downloadSummary
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
