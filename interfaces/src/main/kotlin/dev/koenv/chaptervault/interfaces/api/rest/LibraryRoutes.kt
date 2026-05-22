package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.AddSeriesRequest
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.UpdateSeriesRequest
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
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

fun Route.libraryRoutes(
    libraryRead: LibraryReadApi,
    libraryCommand: LibraryCommandApi,
    taskQueue: TaskQueue,
    fileStorage: ChapterPageSource,
) {
    // GET routes are accessible to any authenticated user
    get("/library/series") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        when (val result = libraryRead.listSeries(PageRequest(page, size.coerceIn(1, 100)))) {
            is Result.Success -> call.respond(
                HttpStatusCode.OK,
                PaginatedResponse(
                    items = result.value.items.map { it.toDto() },
                    page = result.value.page,
                    size = result.value.size,
                    totalItems = result.value.totalItems,
                    totalPages = result.value.totalPages,
                    hasNext = result.value.hasNext,
                    hasPrevious = result.value.hasPrevious,
                )
            )
            is Result.Failure -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
        }
    }

    get("/library/series/{id}") {
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid series ID"); return@get
        }
        when (val result = libraryRead.getSeries(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> when (result.error) {
                is AppError.NotFound -> call.respond(HttpStatusCode.NotFound, result.error.message)
                else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
            }
        }
    }

    get("/library/series/{id}/chapters") {
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid series ID"); return@get
        }
        when (val result = libraryRead.listChapters(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.map { it.toDto() })
            is Result.Failure -> when (result.error) {
                is AppError.NotFound -> call.respond(HttpStatusCode.NotFound, result.error.message)
                else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
            }
        }
    }

    get("/library/chapters/{id}/pages/{index}") {
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid chapter ID"); return@get
        }
        val index = call.parameters["index"]?.toIntOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, "Invalid page index"); return@get }

        val chapter = when (val r = libraryRead.getChapter(id)) {
            is Result.Success -> r.value
            is Result.Failure -> when (r.error) {
                is AppError.NotFound -> { call.respond(HttpStatusCode.NotFound, r.error.message); return@get }
                else -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@get }
            }
        }

        if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) {
            call.respond(HttpStatusCode.Locked, "Chapter not yet downloaded"); return@get
        }

        val page = when (val r = fileStorage.readPage(chapter, index)) {
            is Result.Success -> r.value
            is Result.Failure -> when (r.error) {
                is AppError.NotFound -> { call.respond(HttpStatusCode.NotFound, r.error.message); return@get }
                else -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@get }
            }
        }

        // ETag correctness relies on chapter.updatedAt being bumped on re-download.
        // If a re-download updates content without touching updatedAt, clients will serve stale cached pages.
        val etag = "\"${chapter.id}-${chapter.updatedAt.epochSecond}-${index}\""
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified); return@get
        }

        call.response.headers.append(HttpHeaders.ETag, etag)
        call.response.headers.append(HttpHeaders.CacheControl, "max-age=86400, immutable")
        call.respondBytes(page.data, ContentType.parse(page.mimeType))
    }

    post("/library/series") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respond(HttpStatusCode.Forbidden, "Forbidden"); return@post
        }
        val request = try { call.receive<AddSeriesRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body"); return@post
        }
        when (val result = libraryCommand.addToLibrary(request.connectorId, request.externalId, request.language, request.autoDownload)) {
            is Result.Success -> {
                val series = result.value
                val now = Instant.now()
                val task = Task(
                    id = Id.generate(),
                    type = TaskType.FETCH_SERIES_METADATA,
                    status = TaskStatus.PENDING,
                    targetType = TargetType.SERIES,
                    targetId = series.id,
                    payload = mapOf("connectorId" to series.connectorId, "externalId" to series.externalId, "language" to series.language),
                    createdAt = now,
                    updatedAt = now,
                )
                taskQueue.enqueue(task)
                call.respond(HttpStatusCode.Created, series.toDto())
            }
            is Result.Failure -> when (result.error) {
                is AppError.Conflict -> call.respond(HttpStatusCode.Conflict, result.error.message)
                is AppError.ValidationError -> call.respond(HttpStatusCode.BadRequest, result.error.message)
                else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
            }
        }
    }

    delete("/library/series/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respond(HttpStatusCode.Forbidden, "Forbidden"); return@delete
        }
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid series ID"); return@delete
        }
        when (val result = libraryCommand.removeSeries(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> when (result.error) {
                is AppError.NotFound -> call.respond(HttpStatusCode.NotFound, result.error.message)
                else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
            }
        }
    }

    post("/library/series/{id}/download") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respond(HttpStatusCode.Forbidden, "Forbidden"); return@post
        }
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid series ID"); return@post
        }
        val series = when (val r = libraryRead.getSeries(id)) {
            is Result.Success -> r.value
            is Result.Failure -> when (r.error) {
                is AppError.NotFound -> { call.respond(HttpStatusCode.NotFound, r.error.message); return@post }
                else -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@post }
            }
        }
        val chapters = when (val r = libraryRead.listChapters(id)) {
            is Result.Success -> r.value
            is Result.Failure -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@post }
        }
        val format = series.defaultFormat ?: ChapterFormat.Cbz
        val now = Instant.now()
        val toDownload = chapters.filter {
            it.downloadStatus == DownloadStatus.PENDING || it.downloadStatus == DownloadStatus.FAILED
        }
        for (chapter in toDownload) {
            taskQueue.enqueue(Task(
                id = Id.generate(),
                type = TaskType.DOWNLOAD_CHAPTER,
                status = TaskStatus.PENDING,
                targetType = TargetType.CHAPTER,
                targetId = chapter.id,
                payload = mapOf(
                    "connectorId" to series.connectorId,
                    "chapterId" to chapter.id.toString(),
                    "format" to format.toString(),
                ),
                createdAt = now,
                updatedAt = now,
            ))
        }
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to toDownload.size))
    }

    post("/library/chapters/{id}/download") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respond(HttpStatusCode.Forbidden, "Forbidden"); return@post
        }
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid chapter ID"); return@post
        }
        val chapter = when (val r = libraryRead.getChapter(id)) {
            is Result.Success -> r.value
            is Result.Failure -> when (r.error) {
                is AppError.NotFound -> { call.respond(HttpStatusCode.NotFound, r.error.message); return@post }
                else -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@post }
            }
        }
        val series = when (val r = libraryRead.getSeries(chapter.seriesId)) {
            is Result.Success -> r.value
            is Result.Failure -> { call.respond(HttpStatusCode.InternalServerError, r.error.message); return@post }
        }
        val format = series.defaultFormat ?: ChapterFormat.Cbz
        val now = Instant.now()
        taskQueue.enqueue(Task(
            id = Id.generate(),
            type = TaskType.DOWNLOAD_CHAPTER,
            status = TaskStatus.PENDING,
            targetType = TargetType.CHAPTER,
            targetId = chapter.id,
            payload = mapOf(
                "connectorId" to series.connectorId,
                "chapterId" to chapter.id.toString(),
                "format" to format.toString(),
            ),
            createdAt = now,
            updatedAt = now,
        ))
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to 1))
    }

    patch("/library/series/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respond(HttpStatusCode.Forbidden, "Forbidden"); return@patch
        }
        val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid series ID"); return@patch
        }
        val request = try { call.receive<UpdateSeriesRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body"); return@patch
        }
        val defaultFormat = try {
            request.defaultFormat?.let { ChapterFormat.fromString(it) }
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, "Invalid defaultFormat value"); return@patch
        }
        when (val result = libraryCommand.updateSeries(id, request.autoDownload, defaultFormat)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> when (result.error) {
                is AppError.NotFound -> call.respond(HttpStatusCode.NotFound, result.error.message)
                else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
            }
        }
    }
}
