package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.Pagination
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.download.*
import dev.koenv.chaptervault.database.entity.TaskStatus
import dev.koenv.chaptervault.database.entity.TaskType
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.DownloadTaskRepository
import dev.koenv.chaptervault.database.repository.PersistedTask
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Download routes - manage download jobs.
 */
fun Route.downloadRoutes(
    orchestrator: Orchestrator,
    seriesRepository: SeriesRepository,
    chapterRepository: ChapterRepository,
    downloadTaskRepository: DownloadTaskRepository
) {
    route("/api/v1/downloads") {

        /**
         * POST /api/v1/downloads
         * Create a new download job.
         */
        post {
            val request = try {
                call.receive<CreateDownloadRequest>()
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

            // Validate request - need either seriesId or sourceUrl
            if (request.seriesId.isNullOrBlank() && request.sourceUrl.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Request",
                        status = 400,
                        detail = "Either seriesId or sourceUrl must be provided",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                val targetUrl: String
                var seriesUuid: UUID? = null

                if (!request.seriesId.isNullOrBlank()) {
                    // Download by series ID (from library)
                    seriesUuid = try {
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

                    val series = seriesRepository.findById(seriesUuid)
                    if (series == null) {
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
                    targetUrl = series.sourceUrl
                } else {
                    targetUrl = request.sourceUrl!!
                }

                // Create persistent task
                val taskType = if (request.chapterIds.isNullOrEmpty()) {
                    TaskType.DOWNLOAD_SERIES
                } else {
                    TaskType.DOWNLOAD_CHAPTER
                }

                val persistedTask = downloadTaskRepository.create(
                    taskType = taskType,
                    targetUrl = targetUrl,
                    seriesId = seriesUuid
                )

                // Start the download in background via orchestrator, passing the persisted task ID
                orchestrator.downloadSeries(targetUrl, persistedTask.id)

                call.respond(
                    HttpStatusCode.Accepted,
                    CreateDownloadResponse(
                        downloadId = persistedTask.id.toString(),
                        status = "RUNNING",
                        message = "Download job started"
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.CONNECTOR_NOT_FOUND,
                        title = "No Connector Found",
                        status = 400,
                        detail = e.message ?: "No connector can handle this URL",
                        instance = call.request.local.uri
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Create Download",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * GET /api/v1/downloads
         * List all downloads.
         */
        get {
            val statusFilter = call.request.queryParameters["status"]?.let {
                try {
                    TaskStatus.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

            val allTasks = downloadTaskRepository.findAll(statusFilter)
            val total = allTasks.size.toLong()
            val paginatedTasks = allTasks.drop(offset).take(limit)

            call.respond(
                HttpStatusCode.OK,
                DownloadListResponse(
                    downloads = paginatedTasks.map { it.toStatusResponse() },
                    pagination = Pagination(
                        offset = offset,
                        limit = limit,
                        total = total,
                        hasMore = offset + paginatedTasks.size < total
                    )
                )
            )
        }

        /**
         * GET /api/v1/downloads/{downloadId}
         * Get download status.
         */
        get("/{downloadId}") {
            val downloadIdParam = call.parameters["downloadId"]

            val downloadId = try {
                UUID.fromString(downloadIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Download ID",
                        status = 400,
                        detail = "Invalid UUID format: $downloadIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            val task = downloadTaskRepository.findById(downloadId)
            if (task == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Download Not Found",
                        status = 404,
                        detail = "No download found with ID: $downloadIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, task.toStatusResponse())
        }
    }
}

private fun PersistedTask.toStatusResponse(): DownloadStatusResponse {
    return DownloadStatusResponse(
        id = id.toString(),
        taskType = taskType.name,
        targetUrl = targetUrl,
        seriesId = seriesId?.toString(),
        status = status.name,
        message = message,
        progress = DownloadProgressDto(
            current = currentProgress,
            total = totalProgress,
            percentage = percentage
        ),
        error = errorMessage,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        completedAt = completedAt?.toString()
    )
}
