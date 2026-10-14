package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond

private val logger = KotlinLogging.logger {}

private val emptyBodyErrors =
    mapOf(
        HttpStatusCode.Unauthorized to ErrorResponse("UNAUTHORIZED", "Authentication is required"),
        HttpStatusCode.NotFound to ErrorResponse("NOT_FOUND", "The requested resource was not found"),
        HttpStatusCode.MethodNotAllowed to ErrorResponse("METHOD_NOT_ALLOWED", "HTTP method not allowed for this endpoint"),
    )

/**
 * Answers the errors Ktor produces on its own (failed authentication, unknown route, wrong method) with an
 * [ErrorResponse] instead of an empty body. Responses a route wrote itself, such as `Series 'x' not found`, already
 * carry a body and pass through untouched; the challenge headers of a 401 are kept.
 *
 * Must be installed before ContentNegotiation so the replacement body is serialised.
 */
private val EmptyErrorBodies =
    createApplicationPlugin("EmptyErrorBodies") {
        onCallRespond { call, body ->
            val content = body as? OutgoingContent.NoContent
            val status = (body as? HttpStatusCode) ?: content?.status ?: return@onCallRespond
            val replacement = emptyBodyErrors[status] ?: return@onCallRespond
            content?.headers?.get(HttpHeaders.WWWAuthenticate)?.let { call.response.headers.append(HttpHeaders.WWWAuthenticate, it) }
            call.response.status(status)
            transformBody { replacement }
        }
    }

/** Installs the error handling shared by every route. Call it before installing ContentNegotiation. */
internal fun Application.configureStatusPages() {
    install(EmptyErrorBodies)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception on ${call.request.httpMethod.value} ${call.request.uri}" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
        }
    }
}
