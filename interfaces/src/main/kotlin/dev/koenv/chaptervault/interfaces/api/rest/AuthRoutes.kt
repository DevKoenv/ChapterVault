package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val roles: List<String>)

@Serializable
data class UserResponse(val id: String, val username: String, val roles: List<String>)

fun Application.authRoutes(auth: AuthApi) {
    routing {
        post("/auth/register") {
            val request = try { call.receive<LoginRequest>() } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request"); return@post
            }
            when (val result = auth.register(Credentials(request.username, request.password))) {
                is Result.Success -> call.respond(
                    HttpStatusCode.Created,
                    UserResponse(
                        id = result.value.id.toString(),
                        username = result.value.username,
                        roles = result.value.roles.map { it.name },
                    )
                )
                is Result.Failure -> when (result.error) {
                    is AppError.Conflict -> call.respond(HttpStatusCode.Conflict, result.error.message)
                    else -> call.respond(HttpStatusCode.InternalServerError, result.error.message)
                }
            }
        }

        post("/auth/login") {
            val request = try { call.receive<LoginRequest>() } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request"); return@post
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
                        )
                    )
                }
                is Result.Failure -> call.respond(HttpStatusCode.Unauthorized, result.error.message)
            }
        }

        post("/auth/logout") {
            val token = call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
                ?: run { call.respond(HttpStatusCode.Unauthorized); return@post }
            auth.invalidateSession(token)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
