package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MAX_REQUEST_BODY_BYTES = 10L * 1024 * 1024 // 10 MB

private const val BODY_BUFFER_BYTES = 8 * 1024

private val payloadTooLarge = ErrorResponse("PAYLOAD_TOO_LARGE", "Request body must not exceed 10 MB")

private val BodyLimitExceeded = AttributeKey<Boolean>("RequestBodyLimitExceeded")

internal class PayloadTooLargeException : RuntimeException("Request body exceeds $MAX_REQUEST_BODY_BYTES bytes")

/**
 * Rejects bodies over [MAX_REQUEST_BODY_BYTES] in two layers:
 * the Content-Length header is checked up front (the handler never runs), and the received bytes are
 * counted while streaming, which also covers chunked requests and clients that lie about the length.
 */
internal val RequestBodyLimit =
    createApplicationPlugin("RequestBodyLimit") {
        application.intercept(ApplicationCallPipeline.Plugins) {
            val length = call.request.contentLength()
            if (length != null && length > MAX_REQUEST_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, payloadTooLarge)
                finish()
            }
        }

        onCall { call ->
            call.request.pipeline.intercept(ApplicationReceivePipeline.Before) { body ->
                val source = body as? ByteReadChannel ?: return@intercept
                val limited =
                    call.application
                        .writer(Dispatchers.IO) {
                            val buffer = ByteArray(BODY_BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                val read = source.readAvailable(buffer, 0, buffer.size)
                                if (read < 0) break
                                total += read
                                if (total > MAX_REQUEST_BODY_BYTES) {
                                    call.attributes.put(BodyLimitExceeded, true)
                                    throw PayloadTooLargeException()
                                }
                                channel.writeFully(buffer, 0, read)
                            }
                        }.channel
                proceedWith(limited)
            }
        }

        // the channel wraps the failure in an IOException, so look through the cause chain; this runs inside
        // StatusPages, which keeps this from turning into the generic 500 handler
        on(CallFailed) { call, cause ->
            if (generateSequence(cause) { it.cause }.any { it is PayloadTooLargeException }) {
                call.respond(HttpStatusCode.PayloadTooLarge, payloadTooLarge)
            }
        }

        // handlers may swallow the failure and answer 400; the client still must see a 413.
        // This hook runs after content negotiation, so the replacement body is already serialized.
        onCallRespond { call ->
            if (call.attributes.contains(BodyLimitExceeded) && call.response.status() != HttpStatusCode.PayloadTooLarge) {
                call.response.status(HttpStatusCode.PayloadTooLarge)
                transformBody { TextContent(Json.encodeToString(payloadTooLarge), ContentType.Application.Json) }
            }
        }
    }
