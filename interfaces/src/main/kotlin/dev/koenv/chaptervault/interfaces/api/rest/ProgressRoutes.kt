package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.progressRoutes(progressApi: ProgressApi) {
    get("/library/series/{id}/progress") {
        val principal = call.principal<KtorPrincipal>()!!
        val seriesId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid series ID")
                return@get
            }
        when (val result = progressApi.getProgress(principal.user.id, seriesId)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/chapters/{id}/read") {
        val principal = call.principal<KtorPrincipal>()!!
        val chapterId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@post
            }
        when (val result = progressApi.markRead(principal.user.id, chapterId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/chapters/{id}/read") {
        val principal = call.principal<KtorPrincipal>()!!
        val chapterId =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid chapter ID")
                return@delete
            }
        when (val result = progressApi.markUnread(principal.user.id, chapterId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
