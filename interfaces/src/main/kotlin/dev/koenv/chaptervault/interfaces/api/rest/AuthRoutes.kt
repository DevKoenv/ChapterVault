package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.UserDto
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.PasswordPolicy
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val username: String,
    val roles: List<String>,
)

fun Route.authRoutes(
    auth: AuthApi,
    registrationEnabled: Boolean,
) {
    post("/auth/register") {
        if (!registrationEnabled) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("REGISTRATION_DISABLED", "User registration is disabled on this server"))
            return@post
        }
        val request = call.receiveOrBadRequest<LoginRequest>() ?: return@post
        if (request.username.isBlank() || request.username.length > 100) {
            call.respondBadRequest("username must be 1–100 non-whitespace characters")
            return@post
        }
        PasswordPolicy.violation(request.password)?.let {
            call.respondBadRequest(it)
            return@post
        }
        when (val result = auth.register(Credentials(request.username, request.password))) {
            is Result.Success ->
                call.respond(
                    HttpStatusCode.Created,
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

    post("/auth/login") {
        val request = call.receiveOrBadRequest<LoginRequest>() ?: return@post
        if (request.username.isBlank() || request.username.length > 100) {
            call.respondBadRequest("username must be 1–100 non-whitespace characters")
            return@post
        }
        if (request.password.isEmpty() || request.password.length > 255) {
            call.respondBadRequest("password must be 1–255 characters")
            return@post
        }
        when (val result = auth.authenticate(Credentials(request.username, request.password))) {
            is Result.Success -> {
                val (principal, session) = result.value
                call.respond(
                    HttpStatusCode.OK,
                    LoginResponse(
                        token = session.token,
                        username = principal.username,
                        roles = principal.roles.map { it.name },
                    ),
                )
            }
            is Result.Failure -> call.respondError(result.error)
        }
    }

    delete("/auth/logout") {
        val token =
            call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
                ?: run {
                    call.respondError(AppError.Unauthorized("No token provided"))
                    return@delete
                }
        when (val result = auth.invalidateSession(token)) {
            is Result.Success -> call.respond(HttpStatusCode.NoContent)
            is Result.Failure -> call.respondError(result.error)
        }
    }
}
