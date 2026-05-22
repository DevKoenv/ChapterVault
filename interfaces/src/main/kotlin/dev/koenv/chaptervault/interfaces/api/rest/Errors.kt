package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.shared.result.AppError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

suspend fun ApplicationCall.respondError(error: AppError) {
    val (status, code) = when (error) {
        is AppError.NotFound -> HttpStatusCode.NotFound to "NOT_FOUND"
        is AppError.Conflict -> HttpStatusCode.Conflict to "CONFLICT"
        is AppError.ValidationError -> HttpStatusCode.BadRequest to "VALIDATION_ERROR"
        is AppError.Unauthorized -> HttpStatusCode.Unauthorized to "UNAUTHORIZED"
        is AppError.Forbidden -> HttpStatusCode.Forbidden to "FORBIDDEN"
        is AppError.InternalError -> HttpStatusCode.InternalServerError to "INTERNAL_ERROR"
    }
    respond(status, ErrorResponse(code, error.message))
}

suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", message))

suspend fun ApplicationCall.respondForbidden() =
    respond(HttpStatusCode.Forbidden, ErrorResponse("FORBIDDEN", "Forbidden"))
