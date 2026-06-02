package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.CreateBookmarkRequest
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.BookmarkApi
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.bookmarkRoutes(bookmarkApi: BookmarkApi) {
    get("/library/series/{id}/bookmarks") {
        val principal =
            call.principal<KtorPrincipal>() ?: run {
                call.respondForbidden()
                return@get
            }
        val seriesId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@get
            }
        when (val result = bookmarkApi.list(principal.user.id, seriesId)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.map { it.toDto() })
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/chapters/{id}/bookmarks") {
        val principal =
            call.principal<KtorPrincipal>() ?: run {
                call.respondForbidden()
                return@post
            }
        val chapterId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@post
            }
        val request =
            try {
                call.receive<CreateBookmarkRequest>()
            } catch (e: Exception) {
                call.respondBadRequest("Invalid request body")
                return@post
            }
        when (val result = bookmarkApi.create(principal.user.id, chapterId, request.page)) {
            is Result.Success -> call.respond(HttpStatusCode.Created, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/bookmarks/{id}") {
        val principal =
            call.principal<KtorPrincipal>() ?: run {
                call.respondForbidden()
                return@delete
            }
        val bookmarkId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid bookmark ID")
                return@delete
            }
        when (val result = bookmarkApi.delete(principal.user.id, bookmarkId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
