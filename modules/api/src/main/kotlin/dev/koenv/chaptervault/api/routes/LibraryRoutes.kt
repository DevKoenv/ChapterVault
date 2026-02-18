package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.Pagination
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.catalog.ChapterDto
import dev.koenv.chaptervault.api.models.catalog.SeriesDetailResponse
import dev.koenv.chaptervault.api.models.catalog.SeriesDto
import dev.koenv.chaptervault.api.models.library.*
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
 * Library routes - view downloaded content and manage library membership.
 */
fun Route.libraryRoutes(
    seriesRepository: SeriesRepositoryPort,
    chapterRepository: ChapterRepositoryPort,
    orchestrator: Orchestrator? = null
) {
    route("/api/v1/library") {

        /**
         * GET /api/v1/library/series
         * List all series in the user's library.
         */
        get("/series") {
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

            try {
                val librarySeries = seriesRepository.findAllInLibrary()

                val total = librarySeries.size.toLong()
                val paginatedSeries = librarySeries.drop(offset).take(limit)

                val response = paginatedSeries.map { series ->
                    series.toSeriesDto(chapterRepository)
                }

                call.respond(
                    HttpStatusCode.OK,
                    LibrarySeriesListResponse(
                        series = response,
                        pagination = Pagination(
                            offset = offset,
                            limit = limit,
                            total = total,
                            hasMore = offset + response.size < total
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to List Library",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * GET /api/v1/library/series/{seriesId}
         * Get series with all chapters and their download status.
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
         * POST /api/v1/library/series/{seriesId}
         * Add a series to the user's library.
         */
        post("/series/{seriesId}") {
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

            try {
                val series = seriesRepository.addToLibrary(seriesId)
                call.respond(
                    HttpStatusCode.OK,
                    LibraryAddResponse(
                        id = series.id.toString(),
                        title = series.title,
                        inLibrary = series.inLibrary,
                        addedToLibraryAt = series.addedToLibraryAt?.toString()
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = e.message ?: "Series not found",
                        instance = call.request.local.uri
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Add to Library",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * DELETE /api/v1/library/series/{seriesId}
         * Remove a series from the user's library.
         */
        delete("/series/{seriesId}") {
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
                return@delete
            }

            try {
                val series = seriesRepository.removeFromLibrary(seriesId)
                call.respond(
                    HttpStatusCode.OK,
                    LibraryRemoveResponse(
                        id = series.id.toString(),
                        title = series.title,
                        inLibrary = series.inLibrary
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = e.message ?: "Series not found",
                        instance = call.request.local.uri
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Remove from Library",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
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
