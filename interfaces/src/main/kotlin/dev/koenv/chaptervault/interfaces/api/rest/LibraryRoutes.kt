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
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskPayloadKeys
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
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
        val q = call.request.queryParameters["q"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        val principal = call.principal<KtorPrincipal>()
        when (val result = libraryRead.searchLibrary(q, PageRequest(page, size.coerceIn(1, 100)))) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    PaginatedResponse(
                        items =
                            result.value.items.map { series ->
                                val readingStatus = principal?.let { p -> readingStatusApi.getStatus(p.user.id, series.id) }
                                series.toDto(readingStatus)
                            },
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

    get("/library/series") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        val statusFilter =
            call.request.queryParameters["readingStatus"]?.let { s ->
                runCatching { ReadingStatus.valueOf(s.uppercase()) }.getOrNull()
            }
        val principal = call.principal<KtorPrincipal>()
        when (val result = libraryRead.listSeries(PageRequest(page, size.coerceIn(1, 100)))) {
            is Result.Success -> {
                val items =
                    result.value.items
                        .map { series ->
                            val readingStatus = principal?.let { p -> readingStatusApi.getStatus(p.user.id, series.id) }
                            series.toDto(readingStatus)
                        }.let { dtos ->
                            if (statusFilter != null) {
                                dtos.filter { it.readingStatus == statusFilter.name }
                            } else {
                                dtos
                            }
                        }
                call.respond(
                    HttpStatusCode.OK,
                    PaginatedResponse(
                        items = items,
                        page = result.value.page,
                        size = result.value.size,
                        totalItems = result.value.totalItems,
                        totalPages = result.value.totalPages,
                        hasNext = result.value.hasNext,
                        hasPrevious = result.value.hasPrevious,
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/library/series/{id}") {
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@get
            }
        val principal = call.principal<KtorPrincipal>()
        when (val result = libraryRead.getSeries(id)) {
            is Result.Success -> {
                val readingStatus = principal?.let { p -> readingStatusApi.getStatus(p.user.id, id) }
                call.respond(HttpStatusCode.OK, result.value.toDto(readingStatus))
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/library/series/{id}/chapters") {
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@get
            }
        val statusParam = call.request.queryParameters["status"]
        val statusFilter =
            if (statusParam != null) {
                try {
                    DownloadStatus.valueOf(statusParam.uppercase())
                } catch (e: Exception) {
                    call.respondBadRequest("Invalid status value")
                    return@get
                }
            } else {
                null
            }
        val result =
            if (statusFilter != null) {
                libraryRead.listChaptersByStatus(id, statusFilter)
            } else {
                libraryRead.listChapters(id)
            }
        when (result) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.map { it.toDto() })
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/library/chapters/{id}/pages/{index}") {
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@get
            }
        val index =
            call.parameters["index"]?.toIntOrNull()
                ?: run {
                    call.respondBadRequest("Invalid page index")
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
        call.response.headers.append(HttpHeaders.CacheControl, "max-age=86400, immutable")
        call.respondBytes(page.data, ContentType.parse(page.mimeType))
    }

    post("/library/series") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val request =
            try {
                call.receive<AddSeriesRequest>()
            } catch (e: Exception) {
                call.respondBadRequest("Invalid request body")
                return@post
            }
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
                                TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                                TaskPayloadKeys.EXTERNAL_ID to series.externalId,
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
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@delete
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@delete
            }
        when (val result = libraryCommand.removeSeries(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/series/{id}/download") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@post
            }
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
            taskQueue.enqueue(
                Task(
                    id = Id.generate(),
                    type = TaskType.DOWNLOAD_CHAPTER,
                    status = TaskStatus.PENDING,
                    targetType = TargetType.CHAPTER,
                    targetId = chapter.id,
                    payload =
                        mapOf(
                            TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                            TaskPayloadKeys.CHAPTER_ID to chapter.id.toString(),
                            TaskPayloadKeys.FORMAT to format.toString(),
                        ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to toDownload.size))
    }

    post("/library/chapters/{id}/download") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@post
            }
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
        taskQueue.enqueue(
            Task(
                id = Id.generate(),
                type = TaskType.DOWNLOAD_CHAPTER,
                status = TaskStatus.PENDING,
                targetType = TargetType.CHAPTER,
                targetId = chapter.id,
                payload =
                    mapOf(
                        TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                        TaskPayloadKeys.CHAPTER_ID to chapter.id.toString(),
                        TaskPayloadKeys.FORMAT to format.toString(),
                    ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to 1))
    }

    post("/library/chapters/{id}/redownload") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@post
            }
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
        taskQueue.enqueue(
            Task(
                id = Id.generate(),
                type = TaskType.DOWNLOAD_CHAPTER,
                status = TaskStatus.PENDING,
                targetType = TargetType.CHAPTER,
                targetId = chapter.id,
                payload =
                    mapOf(
                        TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                        TaskPayloadKeys.CHAPTER_ID to chapter.id.toString(),
                        TaskPayloadKeys.FORMAT to format.toString(),
                    ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to 1))
    }

    delete("/library/chapters/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@delete
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@delete
            }
        when (val result = libraryCommand.evictChapter(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/series/{id}/refresh") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@post
            }
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
                        TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                        TaskPayloadKeys.EXTERNAL_ID to series.externalId,
                        TaskPayloadKeys.LANGUAGE to series.language,
                    ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        call.respond(HttpStatusCode.Accepted)
    }

    patch("/library/series/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@patch
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@patch
            }
        val request =
            try {
                call.receive<UpdateSeriesRequest>()
            } catch (e: Exception) {
                call.respondBadRequest("Invalid request body")
                return@patch
            }
        val defaultFormat =
            try {
                request.defaultFormat?.let { ChapterFormat.fromString(it) }
            } catch (e: IllegalArgumentException) {
                call.respondBadRequest("Invalid defaultFormat value")
                return@patch
            }
        when (val result = libraryCommand.updateSeries(id, request.autoDownload, defaultFormat)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
