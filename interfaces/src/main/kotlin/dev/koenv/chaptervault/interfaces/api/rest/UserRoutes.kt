package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.UserDto
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.auth.PasswordPolicy
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class UpdateUserRoleRequest(
    val role: String,
)

fun Route.userRoutes(auth: AuthApi) {
    get("/auth/me") {
        val principal = call.requirePrincipal() ?: return@get
        call.respond(
            HttpStatusCode.OK,
            UserDto(
                id = principal.user.id.toString(),
                username = principal.user.username,
                roles = principal.user.roles.map { it.name },
                createdAt = principal.user.createdAt.toString(),
            ),
        )
    }

    post("/auth/change-password") {
        val principal = call.requirePrincipal() ?: return@post
        val request = call.receiveOrBadRequest<ChangePasswordRequest>() ?: return@post
        PasswordPolicy.violation(request.newPassword)?.let {
            call.respondBadRequest(it)
            return@post
        }
        when (val result = auth.changePassword(principal.user.id, request.currentPassword, request.newPassword)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    get("/users") {
        call.requirePermission(Permission.MANAGE_USERS) ?: return@get
        when (val result = auth.listUsers()) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    result.value.map {
                        UserDto(
                            id = it.id.toString(),
                            username = it.username,
                            roles =
                                it.roles.map { r ->
                                    r.name
                                },
                            createdAt = it.createdAt.toString(),
                        )
                    },
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/users/{id}") {
        val principal = call.requirePermission(Permission.MANAGE_USERS) ?: return@delete
        val id = call.idParam("id", "user") ?: return@delete
        if (id == principal.user.id) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("SELF_DELETE", "Cannot delete your own account"))
            return@delete
        }
        when (val result = auth.deleteUser(id)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }

    patch("/users/{id}/role") {
        call.requirePermission(Permission.MANAGE_USERS) ?: return@patch
        val id = call.idParam("id", "user") ?: return@patch
        val request = call.receiveOrBadRequest<UpdateUserRoleRequest>() ?: return@patch
        val role =
            try {
                Role.valueOf(request.role.uppercase())
            } catch (_: IllegalArgumentException) {
                call.respondBadRequest("Invalid role; must be ADMIN, USER, or GUEST")
                return@patch
            }
        when (val result = auth.updateUserRole(id, role)) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    UserDto(
                        id = result.value.id.toString(),
                        username = result.value.username,
                        roles = result.value.roles.map { it.name },
                        createdAt = result.value.createdAt.toString(),
                    ),
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
