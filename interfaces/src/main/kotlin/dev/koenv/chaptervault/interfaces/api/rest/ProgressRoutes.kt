package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.progressRoutes(progressApi: ProgressApi) {
    get("/library/series/{id}/progress") {
        val principal = call.requirePermission(Permission.READ_LIBRARY) ?: return@get
        val seriesId = call.idParam("id", "series") ?: return@get
        when (val result = progressApi.getProgress(principal.user.id, seriesId)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/library/chapters/{id}/read") {
        val principal = call.requirePermission(Permission.WRITE_PROGRESS) ?: return@post
        val chapterId = call.idParam("id", "chapter") ?: return@post
        when (val result = progressApi.markRead(principal.user.id, chapterId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/library/chapters/{id}/read") {
        val principal = call.requirePermission(Permission.WRITE_PROGRESS) ?: return@delete
        val chapterId = call.idParam("id", "chapter") ?: return@delete
        when (val result = progressApi.markUnread(principal.user.id, chapterId)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
