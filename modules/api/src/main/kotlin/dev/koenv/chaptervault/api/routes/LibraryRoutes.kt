package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.Pagination
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.library.*
import dev.koenv.chaptervault.core.repository.CachedChapter
import dev.koenv.chaptervault.core.repository.CachedSeries
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.DownloadStatus
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Library routes - view downloaded content only.
 */
fun Route.libraryRoutes(
    seriesRepository: SeriesRepositoryPort,
    chapterRepository: ChapterRepositoryPort
) {
    route("/api/v1/library") {

        /**
         * GET /api/v1/library/series
         * List series that have downloaded chapters.
         */
        get("/series") {
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

            try {
                // Get all series that have at least one downloaded chapter
                val allSeries = seriesRepository.findAll()
                val seriesWithDownloads = allSeries.filter { series ->
                    chapterRepository.countDownloaded(series.id) > 0
                }

                val total = seriesWithDownloads.size.toLong()
                val paginatedSeries = seriesWithDownloads.drop(offset).take(limit)

                val response = paginatedSeries.map { series ->
                    series.toLibraryDto(chapterRepository)
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
         * Get series with downloaded chapters only.
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

            // Only return downloaded chapters
            val downloadedChapters = chapterRepository.findDownloaded(seriesId)

            if (downloadedChapters.isEmpty()) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "No Downloaded Content",
                        status = 404,
                        detail = "Series has no downloaded chapters",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            val totalChapterCount = chapterRepository.countBySeriesId(seriesId).toInt()

            call.respond(
                HttpStatusCode.OK,
                LibrarySeriesDetailResponse(
                    id = series.id.toString(),
                    sourceUrl = series.sourceUrl,
                    title = series.title,
                    description = series.description,
                    author = series.author,
                    coverUrl = series.coverUrl,
                    tags = series.tags,
                    status = series.status.name,
                    downloadedChapterCount = downloadedChapters.size,
                    totalChapterCount = totalChapterCount,
                    chapters = downloadedChapters.map { it.toLibraryChapterDto() }
                )
            )
        }
    }
}

private fun CachedSeries.toLibraryDto(chapterRepository: ChapterRepositoryPort): LibrarySeriesDto {
    val downloadedCount = chapterRepository.countDownloaded(id).toInt()
    val totalCount = chapterRepository.countBySeriesId(id).toInt()

    return LibrarySeriesDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        description = description,
        author = author,
        coverUrl = coverUrl,
        tags = tags,
        status = status.name,
        downloadedChapterCount = downloadedCount,
        totalChapterCount = totalCount
    )
}

private fun CachedChapter.toLibraryChapterDto(): LibraryChapterDto {
    return LibraryChapterDto(
        id = id.toString(),
        sourceUrl = sourceUrl,
        title = title,
        chapterNumber = chapterNumber,
        publishDate = publishDate,
        pageCount = pageCount,
        downloadedAt = downloadedAt?.toString(),
        filePath = filePath,
        fileSize = fileSize
    )
}
