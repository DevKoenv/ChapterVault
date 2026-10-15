package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.PaginatedResponse
import dev.koenv.chaptervault.interfaces.serialization.mappers.v1.toDto
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.runtime.TaskQuery
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.taskRoutes(system: SystemApi) {
    get("/tasks") {
        call.requirePermission(Permission.VIEW_TASKS) ?: return@get
        val params = call.request.queryParameters
        val status =
            params["status"]?.let { value ->
                TaskStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: return@get call.respondBadRequest("Invalid status value")
            }
        val type =
            params["type"]?.let { value ->
                TaskType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: return@get call.respondBadRequest("Invalid type value")
            }
        val targetId =
            params["targetId"]?.let { value ->
                runCatching { Id.from(value) }.getOrNull() ?: return@get call.respondBadRequest("Invalid targetId value")
            }
        when (val result = system.listTasks(TaskQuery(status, type, targetId), call.pageRequest(defaultSize = 20))) {
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
