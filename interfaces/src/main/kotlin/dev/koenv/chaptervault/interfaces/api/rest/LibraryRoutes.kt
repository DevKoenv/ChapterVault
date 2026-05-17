package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.AddSeriesRequest
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.libraryRoutes(
    libraryRead: LibraryReadApi,
    libraryCommand: LibraryCommandApi,
) {
    routing {
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

        post("/library/series") {
            val request = try { call.receive<AddSeriesRequest>() } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request body"); return@post
            }
            when (val result = libraryCommand.addToLibrary(request.connectorId, request.externalId, request.autoDownload)) {
                is Result.Success -> call.respond(HttpStatusCode.Created, result.value.toDto())
                is Result.Failure -> when (result.error) {
                    is AppError.Conflict -> call.respond(HttpStatusCode.Conflict, result.error.message)
                    is AppError.ValidationError -> call.respond(HttpStatusCode.BadRequest, result.error.message)
                    else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
                }
            }
        }
    }
}
