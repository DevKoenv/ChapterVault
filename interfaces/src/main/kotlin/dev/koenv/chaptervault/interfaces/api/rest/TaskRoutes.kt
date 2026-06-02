package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.taskRoutes(system: SystemApi) {
    get("/tasks") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        when (val result = system.listTasks(PageRequest(page, size.coerceIn(1, 100)))) {
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

    get("/tasks/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@get
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid task ID")
                return@get
            }
        when (val result = system.getTask(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/tasks/{id}/cancel") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid task ID")
                return@post
            }
        when (val result = system.cancelTask(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
