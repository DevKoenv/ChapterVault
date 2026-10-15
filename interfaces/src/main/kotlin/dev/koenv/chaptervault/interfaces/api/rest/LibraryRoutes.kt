package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.AddSeriesRequest
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.UpdateSeriesRequest
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.api.SeriesQuery
import dev.koenv.chaptervault.kernel.api.SeriesSort
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskPayloadKeys
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.kernel.runtime.downloadChapterTask
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.time.Instant

@Suppress("LongMethod", "CyclomaticComplexMethod")
fun Route.libraryRoutes(
    libraryRead: LibraryReadApi,
    libraryCommand: LibraryCommandApi,
    taskQueue: TaskQueue,
    fileStorage: ChapterPageSource,
    connectorRegistry: ConnectorRegistry,
    readingStatusApi: ReadingStatusApi,
) {
    get("/library/series/search") {
        val principal = call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        call.respondSeriesList(principal.user.id, call.request.queryParameters["q"] ?: "", libraryRead, readingStatusApi)
    }

    get("/library/series") {
        val principal = call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        call.respondSeriesList(principal.user.id, null, libraryRead, readingStatusApi)
    }

    get("/library/series/{id}") {
        val principal = call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        val id = call.idParam("id", "series") ?: return@get
        when (val result = libraryRead.getSeries(id)) {
            is Result.Success -> {
                val readingStatus = readingStatusApi.getStatus(principal.user.id, id)
                call.respond(HttpStatusCode.OK, result.value.toDto(readingStatus))
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/library/series/{id}/chapters") {
        call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        val id = call.idParam("id", "series") ?: return@get
        val statusParam = call.request.queryParameters["status"]
        val statusFilter =
            if (statusParam != null) {
                try {
                    DownloadStatus.valueOf(statusParam.uppercase())
                } catch (_: IllegalArgumentException) {
                    call.respondBadRequest("Invalid status value")
                    return@get
                }
            } else {
                null
            }
        when (
            val result =
                libraryRead.listChaptersPaginated(
                    id,
                    statusFilter,
                    call.pageRequest(defaultSize = 50, maxSize = PageRequest.MAX_SIZE),
                )
        ) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    PaginatedResponse(
                        items = result.value.items.map { it.toDto() },
                        page = result.value.page,
                        size = result.value.size,
                        totalItems = result.value.totalItems,
                        totalPages = result.value.totalPages,
                        hasNext = result.value.hasNext,
                        hasPrevious = result.value.hasPrevious,
                    ),
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/library/chapters/{id}/pages/{index}") {
        call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        val id = call.idParam("id", "chapter") ?: return@get
        val index =
            call.parameters["index"]?.toIntOrNull()
                ?: run {
                    call.respondBadRequest("Invalid page index")
                    return@get
                }

        if (index < 0) {
            call.respondError(AppError.NotFound("Page", index.toString()))
            return@get
        }

        val chapter =
            when (val r = libraryRead.getChapter(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@get
                }
            }

        if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) {
            call.respond(HttpStatusCode.Locked, ErrorResponse("CHAPTER_NOT_DOWNLOADED", "Chapter not yet downloaded"))
            return@get
        }

        val page =
            when (val r = fileStorage.readPage(chapter, index)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@get
                }
            }

        // ETag validity depends on chapter.updatedAt being bumped on re-download
        val etag = "\"${chapter.id}-${chapter.updatedAt.epochSecond}-${index}\""
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        call.response.headers.append(HttpHeaders.ETag, etag)
        call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400, immutable")
        call.respondBytes(page.data, ContentType.parse(page.mimeType))
    }

    post("/library/series") {
        call.requirePermission(Permission.WRITE_LIBRARY) ?: return@post
        val request = call.receiveOrBadRequest<AddSeriesRequest>() ?: return@post
        val connector =
            connectorRegistry.findById(request.connectorId)
                ?: run {
                    call.respondBadRequest("Unknown connector: ${request.connectorId}")
                    return@post
                }
        if (request.language !in connector.supportedLanguages()) {
            call.respondBadRequest(
                "Language '${request.language}' not supported by connector '${request.connectorId}'. " +
                    "Supported: ${connector.supportedLanguages().joinToString()}",
            )
            return@post
        }
        when (val result = libraryCommand.addToLibrary(request.connectorId, request.externalId, request.language, request.autoDownload)) {
            is Result.Success -> {
                val series = result.value
                val now = Instant.now()
                val task =
                    Task(
                        id = Id.generate(),
                        type = TaskType.FETCH_SERIES_METADATA,
                        status = TaskStatus.PENDING,
                        targetType = TargetType.SERIES,
                        targetId = series.id,
                        payload =
                            mapOf(
                                TaskPayloadKeys.CONNECTOR_ID to (series.connectorId ?: ""),
                                TaskPayloadKeys.EXTERNAL_ID to (series.externalId ?: ""),
                                TaskPayloadKeys.LANGUAGE to series.language,
                            ),
                        createdAt = now,
                        updatedAt = now,
                    )
                taskQueue.enqueue(task)
                call.respond(HttpStatusCode.Created, series.toDto())
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/series/{id}") {
        call.requirePermission(Permission.WRITE_LIBRARY) ?: return@delete
        val id = call.idParam("id", "series") ?: return@delete
        when (val result = libraryCommand.removeSeries(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/series/{id}/download") {
        call.requirePermission(Permission.TRIGGER_TASKS) ?: return@post
        val id = call.idParam("id", "series") ?: return@post
        val series =
            when (val r = libraryRead.getSeries(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val chapters =
            when (val r = libraryRead.listChapters(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val format = series.defaultFormat ?: ChapterFormat.Cbz
        val now = Instant.now()
        val toDownload =
            chapters.filter {
                it.downloadStatus == DownloadStatus.AVAILABLE || it.downloadStatus == DownloadStatus.FAILED
            }
        for (chapter in toDownload) {
            libraryCommand.markChapterPending(chapter.id)
            taskQueue.enqueue(downloadChapterTask(chapter.id, series.connectorId ?: "", format.toString(), now))
        }
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to toDownload.size))
    }

    post("/library/chapters/{id}/download") {
        call.requirePermission(Permission.TRIGGER_TASKS) ?: return@post
        val id = call.idParam("id", "chapter") ?: return@post
        val chapter =
            when (val r = libraryRead.getChapter(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val series =
            when (val r = libraryRead.getSeries(chapter.seriesId)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val format = series.defaultFormat ?: ChapterFormat.Cbz
        val now = Instant.now()
        libraryCommand.markChapterPending(chapter.id)
        taskQueue.enqueue(downloadChapterTask(chapter.id, series.connectorId ?: "", format.toString(), now))
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to 1))
    }

    post("/library/chapters/{id}/redownload") {
        call.requirePermission(Permission.TRIGGER_TASKS) ?: return@post
        val id = call.idParam("id", "chapter") ?: return@post
        val chapter =
            when (val r = libraryRead.getChapter(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val series =
            when (val r = libraryRead.getSeries(chapter.seriesId)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val format = series.defaultFormat ?: ChapterFormat.Cbz
        val now = Instant.now()
        libraryCommand.markChapterPending(chapter.id)
        taskQueue.enqueue(downloadChapterTask(chapter.id, series.connectorId ?: "", format.toString(), now))
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to 1))
    }

    delete("/library/chapters/{id}") {
        call.requirePermission(Permission.WRITE_LIBRARY) ?: return@delete
        val id = call.idParam("id", "chapter") ?: return@delete
        when (val result = libraryCommand.evictChapter(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/series/{id}/refresh") {
        call.requirePermission(Permission.TRIGGER_TASKS) ?: return@post
        val id = call.idParam("id", "series") ?: return@post
        val series =
            when (val r = libraryRead.getSeries(id)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    call.respondError(r.error)
                    return@post
                }
            }
        val now = Instant.now()
        taskQueue.enqueue(
            Task(
                id = Id.generate(),
                type = TaskType.FETCH_SERIES_METADATA,
                status = TaskStatus.PENDING,
                targetType = TargetType.SERIES,
                targetId = series.id,
                payload =
                    mapOf(
                        TaskPayloadKeys.CONNECTOR_ID to (series.connectorId ?: ""),
                        TaskPayloadKeys.EXTERNAL_ID to (series.externalId ?: ""),
                        TaskPayloadKeys.LANGUAGE to series.language,
                    ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        call.respond(HttpStatusCode.Accepted)
    }

    patch("/library/series/{id}") {
        call.requirePermission(Permission.WRITE_LIBRARY) ?: return@patch
        val id = call.idParam("id", "series") ?: return@patch
        val request = call.receiveOrBadRequest<UpdateSeriesRequest>() ?: return@patch
        val defaultFormat =
            try {
                request.defaultFormat?.let { ChapterFormat.fromString(it) }
            } catch (e: IllegalArgumentException) {
                call.respondBadRequest("Invalid defaultFormat value")
                return@patch
            }
        val upstreamStatus =
            try {
                request.upstreamStatus?.let { UpstreamStatus.valueOf(it.uppercase()) }
            } catch (e: IllegalArgumentException) {
                call.respondBadRequest("Invalid upstreamStatus value")
                return@patch
            }
        when (
            val result =
                libraryCommand.updateSeries(
                    id = id,
                    autoDownload = request.autoDownload,
                    defaultFormat = defaultFormat,
                    title = request.title,
                    coverUrl = request.coverUrl,
                    description = request.description,
                    author = request.author,
                    artist = request.artist,
                    year = request.year,
                    upstreamStatus = upstreamStatus,
                    genres = request.genres,
                    clearFields = request.clearFields?.toSet() ?: emptySet(),
                )
        ) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }
}

// GET /library/series and /library/series/search take the same sort, order and filters; a reading status is the caller's own
private suspend fun ApplicationCall.respondSeriesList(
    userId: Id,
    titleContains: String?,
    libraryRead: LibraryReadApi,
    readingStatusApi: ReadingStatusApi,
) {
    val params = request.queryParameters
    val sort =
        params["sort"]?.let { value ->
            SeriesSort.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: return respondBadRequest("Invalid sort value")
        } ?: SeriesSort.TITLE
    val descending =
        when (params["order"]?.lowercase()) {
            null -> sort.defaultDescending
            "asc" -> false
            "desc" -> true
            else -> return respondBadRequest("Invalid order value")
        }
    val upstreamStatus =
        params["upstreamStatus"]?.let { value ->
            UpstreamStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.name
                ?: return respondBadRequest("Invalid upstreamStatus value")
        }
    val readingStatus =
        params["readingStatus"]?.let { value ->
            ReadingStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: return respondBadRequest("Invalid readingStatus value")
        }
    val query =
        SeriesQuery(
            sort = sort,
            descending = descending,
            ids = readingStatus?.let { readingStatusApi.getSeriesIdsWithStatus(userId, it) },
            titleContains = titleContains,
            genre = params["genre"]?.takeIf { it.isNotBlank() },
            language = params["language"]?.takeIf { it.isNotBlank() },
            upstreamStatus = upstreamStatus,
        )
    when (val result = libraryRead.listSeries(query, pageRequest(defaultSize = 20))) {
        is Result.Success -> {
            val page = result.value
            val statuses = if (readingStatus != null) emptyMap() else readingStatusApi.getAllStatuses(userId, page.items.map { it.id })
            respond(
                HttpStatusCode.OK,
                PaginatedResponse(
                    items = page.items.map { it.toDto(readingStatus ?: statuses[it.id]) },
                    page = page.page,
                    size = page.size,
                    totalItems = page.totalItems,
                    totalPages = page.totalPages,
                    hasNext = page.hasNext,
                    hasPrevious = page.hasPrevious,
                ),
            )
        }
        is Result.Failure -> respondError(result.error)
    }
}
