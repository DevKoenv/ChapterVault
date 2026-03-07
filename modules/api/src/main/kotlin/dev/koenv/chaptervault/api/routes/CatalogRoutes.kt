package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.catalog.*
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.repository.Chapter
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.Series
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
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
         * GET /api/v1/catalog/search?q=&url=&connector=
         * Search for series by keyword or URL.
         *
         * At least one of `q` or `url` must be provided.
         * `connector` is optional; for `q`, scopes search to one connector.
         * For `url`, the connector is resolved automatically.
         */
        get("/search") {
            val url = call.request.queryParameters["url"]?.trim()?.takeIf { it.isNotBlank() }
            val query = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
            val connectorParam = call.request.queryParameters["connector"]?.trim()?.takeIf { it.isNotBlank() }

            if (url == null && query == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Missing Parameter",
                        status = 400,
                        detail = "At least one of 'q' or 'url' must be provided",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            if (url != null) {
                // URL lookup — connector resolved from URL
                val connector = connectorRegistry.findConnector(url)
                if (connector == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProblemDetail(
                            type = ErrorTypes.VALIDATION,
                            title = "Unsupported URL",
                            status = 400,
                            detail = "No connector can handle the provided URL",
                            instance = call.request.local.uri
                        )
                    )
                    return@get
                }

                try {
                    val metadata = orchestrator.fetchSeriesMetadata(url)
                    val series = seriesRepository.upsert(metadata, null)

                    val chapters = orchestrator.fetchChapterList(url)
                    chapterRepository.saveAll(chapters, series.id)

                    call.respond(
                        HttpStatusCode.OK,
                        CatalogSearchResponse(
                            series = listOf(series.toSeriesDto(chapterRepository)),
                            connector = connector.config.id
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
                // Keyword search
                if (connectorParam != null && connectorRegistry.findById(connectorParam) == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProblemDetail(
                            type = ErrorTypes.VALIDATION,
                            title = "Invalid Connector",
                            status = 400,
                            detail = "Unknown connector: $connectorParam",
                            instance = call.request.local.uri
                        )
                    )
                    return@get
                }

                try {
                    val searchResults = orchestrator.searchSeries(query!!, connectorParam)
                    val seriesList = seriesRepository.upsertAllFromSearch(searchResults)

                    call.respond(
                        HttpStatusCode.OK,
                        CatalogSearchResponse(
                            series = seriesList.map { it.toSeriesDto(chapterRepository) },
                            connector = connectorParam
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
         * Get series detail. Always fetches fresh metadata from the source connector.
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

            val existing = seriesRepository.findById(seriesId)
            if (existing == null) {
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

            val series = try {
                val freshMetadata = orchestrator.fetchSeriesMetadata(existing.sourceUrl)
                val updated = seriesRepository.upsert(freshMetadata, existing.language)

                val chapters = orchestrator.fetchChapterList(existing.sourceUrl)
                chapterRepository.saveAll(chapters, seriesId)

                updated
            } catch (_: Exception) {
                existing
            }

            val allChapters = chapterRepository.findBySeriesId(seriesId)
            val totalChapters = allChapters.size
            val downloadedChapters = chapterRepository.countDownloaded(seriesId).toInt()

            call.respond(
                HttpStatusCode.OK,
                SeriesDetailResponse(
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
                    chapters = allChapters.map { it.toChapterDto() }
                )
            )
        }

        /**
         * GET /api/v1/catalog/series/{seriesId}/chapters
         * Full chapter list for a series.
         */
        get("/series/{seriesId}/chapters") {
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

            if (seriesRepository.findById(seriesId) == null) {
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

            call.respond(
                HttpStatusCode.OK,
                ChapterListResponse(chapters = chapterRepository.findBySeriesId(seriesId).map { it.toChapterDto() })
            )
        }
    }
}

private fun Series.toSeriesDto(chapterRepository: ChapterRepositoryPort): SeriesDto {
    val totalChapters = chapterRepository.countBySeriesId(id).toInt()
    val downloadedChapters = chapterRepository.countDownloaded(id).toInt()
    return SeriesDto(
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

private fun Chapter.toChapterDto(): ChapterDto {
    return ChapterDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        chapterNumber = chapterNumber,
        publishDate = publishDate,
        pageCount = pageCount,
        downloadStatus = downloadStatus.name,
        downloadedAt = downloadedAt?.toString(),
        filePath = filePath,
        fileSize = fileSize
    )
}
