package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.taskRoutes(system: SystemApi) {
    get("/tasks") {
        call.requirePermission(Permission.VIEW_TASKS) ?: return@get
        when (val result = system.listTasks(call.pageRequest(defaultSize = 20))) {
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
        call.requirePermission(Permission.VIEW_TASKS) ?: return@get
        val id = call.idParam("id", "task") ?: return@get
        when (val result = system.getTask(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toDto())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/tasks/{id}/cancel") {
        call.requirePermission(Permission.TRIGGER_TASKS) ?: return@post
        val id = call.idParam("id", "task") ?: return@post
        when (val result = system.cancelTask(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
