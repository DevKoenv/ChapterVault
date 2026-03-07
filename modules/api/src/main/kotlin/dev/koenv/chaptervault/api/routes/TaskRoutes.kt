package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.models.task.TaskCreatedResponse
import dev.koenv.chaptervault.api.models.task.TaskListResponse
import dev.koenv.chaptervault.api.models.task.TaskProgressDto
import dev.koenv.chaptervault.api.models.task.TaskStatusResponse
import dev.koenv.chaptervault.core.repository.PersistedTask
import dev.koenv.chaptervault.core.repository.TaskRepositoryPort
import dev.koenv.chaptervault.core.repository.TaskStatus
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Task routes - manage background jobs (downloads, etc.).
 */
fun Route.taskRoutes(taskRepository: TaskRepositoryPort) {
    route("/api/v1/tasks") {

        /**
         * GET /api/v1/tasks?status=
         * List all tasks, optionally filtered by status.
         */
        get {
            val statusFilter = call.request.queryParameters["status"]?.let {
                try {
                    TaskStatus.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            call.respond(
                HttpStatusCode.OK,
                TaskListResponse(tasks = taskRepository.findAll(statusFilter).map { it.toStatusResponse() })
            )
        }

        /**
         * GET /api/v1/tasks/{taskId}
         * Get task status and progress.
         */
        get("/{taskId}") {
            val taskIdParam = call.parameters["taskId"]

            val taskId = try {
                UUID.fromString(taskIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Task ID",
                        status = 400,
                        detail = "Invalid UUID format: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            val task = taskRepository.findById(taskId)
            if (task == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Task Not Found",
                        status = 404,
                        detail = "No task found with ID: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, task.toStatusResponse())
        }

        /**
         * POST /api/v1/tasks/{taskId}/cancel
         * Cancel a running or pending task.
         */
        post("/{taskId}/cancel") {
            val taskIdParam = call.parameters["taskId"]

            val taskId = try {
                UUID.fromString(taskIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Task ID",
                        status = 400,
                        detail = "Invalid UUID format: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            val task = taskRepository.findById(taskId)
            if (task == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Task Not Found",
                        status = 404,
                        detail = "No task found with ID: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            if (task.status != TaskStatus.PENDING && task.status != TaskStatus.RUNNING) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ProblemDetail(
                        type = ErrorTypes.CONFLICT,
                        title = "Cannot Cancel",
                        status = 409,
                        detail = "Task is already ${task.status.name.lowercase()}",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            taskRepository.markCancelled(taskId)
            call.respond(
                HttpStatusCode.OK,
                TaskCreatedResponse(
                    taskId = taskId.toString(),
                    status = "CANCELLED",
                    message = "Task cancelled"
                )
            )
        }

        /**
         * DELETE /api/v1/tasks/{taskId}
         * Delete a task from history (cancels first if still running).
         */
        delete("/{taskId}") {
            val taskIdParam = call.parameters["taskId"]

            val taskId = try {
                UUID.fromString(taskIdParam)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        type = ErrorTypes.VALIDATION,
                        title = "Invalid Task ID",
                        status = 400,
                        detail = "Invalid UUID format: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@delete
            }

            val task = taskRepository.findById(taskId)
            if (task == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProblemDetail(
                        type = ErrorTypes.NOT_FOUND,
                        title = "Task Not Found",
                        status = 404,
                        detail = "No task found with ID: $taskIdParam",
                        instance = call.request.local.uri
                    )
                )
                return@delete
            }

            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PENDING) {
                taskRepository.markCancelled(taskId)
            }

            taskRepository.delete(taskId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun PersistedTask.toStatusResponse(): TaskStatusResponse {
    return TaskStatusResponse(
        id = id.toString(),
        taskType = taskType.name,
        targetUrl = targetUrl,
        targetType = targetType.name,
        targetId = targetId?.toString(),
        status = status.name,
        message = message,
        progress = TaskProgressDto(
            current = currentProgress,
            total = totalProgress,
            percentage = percentage
        ),
        error = errorMessage,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        completedAt = completedAt?.toString()
    )
}
