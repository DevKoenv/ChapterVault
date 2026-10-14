package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
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
    // the token itself is never returned; send a new one with PATCH to change it, or an empty string to remove it
    val hasToken: Boolean,
    val enabled: Boolean,
    val createdAt: String,
)

private fun NotificationTarget.toResponse() =
    NotificationTargetResponse(
        id = id.toString(),
        name = name,
        type = type,
        url = url,
        hasToken = !token.isNullOrEmpty(),
        enabled = enabled,
        createdAt = createdAt.toString(),
    )

fun Route.notificationRoutes(
    notificationApi: NotificationApi,
    dispatchApi: NotificationDispatchApi,
) {
    get("/notifications") {
        call.requirePermission(Permission.MANAGE_NOTIFICATIONS) ?: return@get
        val targets = notificationApi.listTargets()
        call.respond(HttpStatusCode.OK, targets.map { it.toResponse() })
    }

    post("/notifications") {
        call.requirePermission(Permission.MANAGE_NOTIFICATIONS) ?: return@post
        val req = call.receiveOrBadRequest<CreateTargetRequest>() ?: return@post
        if (req.name.isBlank()) {
            call.respondBadRequest("name must not be blank")
            return@post
        }
        if (req.url.isBlank()) {
            call.respondBadRequest("url must not be blank")
            return@post
        }
        val input =
            NotificationTargetInput(
                name = req.name,
                type = req.type,
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
        call.requirePermission(Permission.MANAGE_NOTIFICATIONS) ?: return@patch
        val id = call.idParam("id", "notification target") ?: return@patch
        val req = call.receiveOrBadRequest<PatchTargetRequest>() ?: return@patch
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
        call.requirePermission(Permission.MANAGE_NOTIFICATIONS) ?: return@delete
        val id = call.idParam("id", "notification target") ?: return@delete
        when (val result = notificationApi.deleteTarget(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/notifications/{id}/test") {
        call.requirePermission(Permission.MANAGE_NOTIFICATIONS) ?: return@post
        val id = call.idParam("id", "notification target") ?: return@post
        when (val result = dispatchApi.sendTest(id)) {
            is Result.Success -> call.respond(HttpStatusCode.OK)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
