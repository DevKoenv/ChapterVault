package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond

private const val MAX_REQUEST_BODY_BYTES = 10L * 1024 * 1024 // 10 MB

internal val RequestBodyLimit =
    createApplicationPlugin("RequestBodyLimit") {
        onCall { call ->
            val length = call.request.contentLength()
            if (length != null && length > MAX_REQUEST_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("PAYLOAD_TOO_LARGE", "Request body must not exceed 10 MB"))
            }
        }
    }
