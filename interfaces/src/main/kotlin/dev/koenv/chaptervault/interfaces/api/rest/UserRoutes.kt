package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.UserDto
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.PasswordGenerator
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

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String? = null,
    val role: String = "USER",
)

@Serializable
data class CreatedUserDto(
    val id: String,
    val username: String,
    val roles: List<String>,
    val createdAt: String,
    // only when the server chose the password; it is not stored in a readable form, so this is the one chance to see it
    val generatedPassword: String? = null,
)

@Serializable
data class SetPasswordRequest(
    val password: String? = null,
)

@Serializable
data class GeneratedPasswordDto(
    val generatedPassword: String,
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

    userAccountRoutes(auth)

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

// creating accounts and resetting passwords (MANAGE_USERS)
private fun Route.userAccountRoutes(auth: AuthApi) {
    // works with registration turned off: an administrator adds the accounts
    post("/users") {
        call.requirePermission(Permission.MANAGE_USERS) ?: return@post
        val request = call.receiveOrBadRequest<CreateUserRequest>() ?: return@post
        val role =
            Role.entries.firstOrNull { it.name.equals(request.role, ignoreCase = true) }
                ?: return@post call.respondBadRequest("Invalid role; must be ADMIN, USER, or GUEST")
        request.password?.let { chosen -> PasswordPolicy.violation(chosen)?.let { return@post call.respondBadRequest(it) } }
        val password = request.password ?: PasswordGenerator.generate()
        when (val result = auth.register(Credentials(request.username, password), role)) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.Created,
                    with(result.value) {
                        CreatedUserDto(
                            id.toString(),
                            username,
                            roles.map { it.name },
                            createdAt.toString(),
                            password.takeIf {
                                request.password ==
                                    null
                            },
                        )
                    },
                )
            is Result.Failure -> call.respondError(result.error)
        }
    }

    post("/users/{id}/password") {
        val principal = call.requirePermission(Permission.MANAGE_USERS) ?: return@post
        val id = call.idParam("id", "user") ?: return@post
        if (id == principal.user.id) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("SELF_PASSWORD_RESET", "Use /auth/change-password for your own account"))
            return@post
        }
        val request = call.receiveOrBadRequest<SetPasswordRequest>() ?: return@post
        request.password?.let { chosen -> PasswordPolicy.violation(chosen)?.let { return@post call.respondBadRequest(it) } }
        val password = request.password ?: PasswordGenerator.generate()
        when (val result = auth.setPassword(id, password)) {
            is Result.Success ->
                if (request.password == null) {
                    call.respond(HttpStatusCode.OK, GeneratedPasswordDto(password))
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
