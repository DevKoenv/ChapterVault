package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.kernel.api.NotificationType
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class CreateTargetRequest(
    val name: String,
    val type: String,
    val url: String,
    val token: String? = null,
    val enabled: Boolean = true,
)

@Serializable
private data class PatchTargetRequest(
    val name: String? = null,
    val url: String? = null,
    val token: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
private data class NotificationTargetResponse(
    val id: String,
    val name: String,
    val type: String,
    val url: String,
    val token: String?,
    val enabled: Boolean,
    val createdAt: String,
)

private fun NotificationTarget.toResponse() =
    NotificationTargetResponse(
        id = id.toString(),
        name = name,
        type = type.name,
        url = url,
        token = token,
        enabled = enabled,
        createdAt = createdAt.toString(),
    )

fun Route.notificationRoutes(
    notificationApi: NotificationApi,
    dispatchApi: NotificationDispatchApi,
) {
    get("/notifications") {
        val targets = notificationApi.listTargets()
        call.respond(HttpStatusCode.OK, targets.map { it.toResponse() })
    }

    post("/notifications") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val req =
            try {
                call.receive<CreateTargetRequest>()
            } catch (e: Exception) {
                call.respondBadRequest("Invalid request body")
                return@post
            }
        if (req.name.isBlank()) {
            call.respondBadRequest("name must not be blank")
            return@post
        }
        if (req.url.isBlank()) {
            call.respondBadRequest("url must not be blank")
            return@post
        }
        val type =
            try {
                NotificationType.valueOf(req.type)
            } catch (e: IllegalArgumentException) {
                call.respondBadRequest("Invalid notification type: ${req.type}")
                return@post
            }
        val input =
            NotificationTargetInput(
                name = req.name,
                type = type,
                url = req.url,
                token = req.token,
                enabled = req.enabled,
            )
        when (val result = notificationApi.createTarget(input)) {
            is Result.Success -> call.respond(HttpStatusCode.Created, result.value.toResponse())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    patch("/notifications/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@patch
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid notification target ID")
                return@patch
            }
        val req =
            try {
                call.receive<PatchTargetRequest>()
            } catch (e: Exception) {
                call.respondBadRequest("Invalid request body")
                return@patch
            }
        val patch =
            NotificationTargetPatch(
                name = req.name,
                url = req.url,
                token = req.token,
                enabled = req.enabled,
            )
        when (val result = notificationApi.updateTarget(id, patch)) {
            is Result.Success -> call.respond(HttpStatusCode.OK, result.value.toResponse())
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/notifications/{id}") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@delete
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid notification target ID")
                return@delete
            }
        when (val result = notificationApi.deleteTarget(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/notifications/{id}/test") {
        val principal = call.principal<KtorPrincipal>()
        if (principal == null || !principal.user.hasRole(Role.ADMIN)) {
            call.respondForbidden()
            return@post
        }
        val id =
            try {
                Id.from(call.parameters["id"]!!)
            } catch (e: Exception) {
                call.respondBadRequest("Invalid notification target ID")
                return@post
            }
        when (val result = dispatchApi.sendTest(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
