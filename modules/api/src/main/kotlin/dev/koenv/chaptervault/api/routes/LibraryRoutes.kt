package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.catalog.ChapterDto
import dev.koenv.chaptervault.api.models.catalog.ChapterListResponse
import dev.koenv.chaptervault.api.models.catalog.SeriesDetailResponse
import dev.koenv.chaptervault.api.models.catalog.SeriesDto
import dev.koenv.chaptervault.api.models.library.*
import dev.koenv.chaptervault.api.models.task.TaskCreatedResponse
import dev.koenv.chaptervault.core.repository.Chapter
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.TaskRepositoryPort
import dev.koenv.chaptervault.core.repository.TaskTargetType
import dev.koenv.chaptervault.core.repository.Series
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.core.repository.TaskType
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Library routes - manage library membership and trigger downloads.
 */
fun Route.libraryRoutes(
    seriesRepository: SeriesRepositoryPort,
    chapterRepository: ChapterRepositoryPort,
    orchestrator: Orchestrator,
    taskRepository: TaskRepositoryPort
) {
    route("/api/v1/library") {

        /**
         * GET /api/v1/library/series
         * List all series in the user's library.
         */
        get("/series") {
            try {
                val series = seriesRepository.findAllInLibrary().map { it.toSeriesDto(chapterRepository) }
                call.respond(HttpStatusCode.OK, LibrarySeriesListResponse(series = series))
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
         * POST /api/v1/library/series
         * Add a series to the user's library.
         *
         * Body: { seriesId: String, autoDownload: Boolean }
         */
        post("/series") {
            val request = try {
                call.receive<LibraryAddRequest>()
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

            val seriesId = try {
                UUID.fromString(request.seriesId)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Series ID",
                        status = 400,
                        detail = "Invalid UUID format: ${request.seriesId}",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            val existing = seriesRepository.findById(seriesId)
            if (existing == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = "No series found with ID: ${request.seriesId}",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                val series = seriesRepository.addToLibrary(seriesId, request.autoDownload)

                var taskId: String? = null
                if (request.autoDownload) {
                    val persistedTask = taskRepository.create(
                        type = TaskType.DOWNLOAD_SERIES,
                        targetUrl = series.sourceUrl,
                        targetType = TaskTargetType.SERIES,
                        targetId = seriesId
                    )
                    orchestrator.downloadSeries(series.sourceUrl, persistedTask.id)
                    taskId = persistedTask.id.toString()
                }

                call.respond(
                    HttpStatusCode.OK,
                    LibraryAddResponse(
                        id = series.id.toString(),
                        title = series.title,
                        inLibrary = series.inLibrary,
                        addedToLibraryAt = series.addedToLibraryAt?.toString(),
                        taskId = taskId
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

        /**
         * GET /api/v1/library/series/{seriesId}/chapters
         * Full chapter list with download state for a library series.
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

        /**
         * POST /api/v1/library/series/{seriesId}/refresh
         * Refresh metadata and chapter list from the source connector.
         * Series must be in the library.
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
            if (existingSeries == null || !existingSeries.inLibrary) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Series Not Found",
                        status = 404,
                        detail = "No library series found with ID: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                val freshMetadata = orchestrator.fetchSeriesMetadata(existingSeries.sourceUrl)
                val updatedSeries = seriesRepository.upsert(freshMetadata, existingSeries.connector, existingSeries.language)

                val chapters = orchestrator.fetchChapterList(existingSeries.sourceUrl)
                chapterRepository.saveAll(chapters, seriesId, existingSeries.connector)
                seriesRepository.stampChaptersFetchedAt(seriesId)

                val allChapters = chapterRepository.findBySeriesId(seriesId)
                val totalChapters = allChapters.size
                val downloadedChapters = chapterRepository.countDownloaded(seriesId).toInt()

                call.respond(
                    HttpStatusCode.OK,
                    SeriesDetailResponse(
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
                        chapters = allChapters.map { it.toChapterDto() }
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

        /**
         * POST /api/v1/library/series/{seriesId}/download
         * Trigger a full series download.
         */
        post("/series/{seriesId}/download") {
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
                return@post
            }

            try {
                val persistedTask = taskRepository.create(
                    type = TaskType.DOWNLOAD_SERIES,
                    targetUrl = series.sourceUrl,
                    targetType = TaskTargetType.SERIES,
                    targetId = seriesId
                )
                orchestrator.downloadSeries(series.sourceUrl, persistedTask.id)

                call.respond(
                    HttpStatusCode.Accepted,
                    TaskCreatedResponse(
                        taskId = persistedTask.id.toString(),
                        status = "PENDING",
                        message = "Downloading entire series"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Start Download",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * POST /api/v1/library/series/{seriesId}/chapters/{chapterId}/download
         * Trigger download of a single chapter.
         */
        post("/series/{seriesId}/chapters/{chapterId}/download") {
            val seriesIdParam = call.parameters["seriesId"]
            val chapterIdParam = call.parameters["chapterId"]

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

            val chapterId = try {
                UUID.fromString(chapterIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Chapter ID",
                        status = 400,
                        detail = "Invalid UUID format: $chapterIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
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
                return@post
            }

            val chapter = chapterRepository.findById(chapterId)
            if (chapter == null || chapter.seriesId != seriesId) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Chapter Not Found",
                        status = 404,
                        detail = "No chapter found with ID: $chapterIdParam for series: $seriesIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                val persistedTask = taskRepository.create(
                    type = TaskType.DOWNLOAD_CHAPTER,
                    targetUrl = chapter.sourceUrl,
                    targetType = TaskTargetType.CHAPTER,
                    targetId = chapterId
                )
                orchestrator.downloadChapters(seriesId, listOf(chapterId), persistedTask.id)

                call.respond(
                    HttpStatusCode.Accepted,
                    TaskCreatedResponse(
                        taskId = persistedTask.id.toString(),
                        status = "PENDING",
                        message = "Downloading chapter"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Start Download",
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
