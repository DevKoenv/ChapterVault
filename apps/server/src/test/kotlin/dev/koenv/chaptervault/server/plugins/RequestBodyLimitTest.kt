package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestBodyLimitTest {
    private val overLimit = MAX_REQUEST_BODY_BYTES.toInt() + 1

    private fun ApplicationTestBuilder.setup(handlerCalled: AtomicBoolean = AtomicBoolean(false)) {
        application {
            install(ContentNegotiation) { json() }
            install(RequestBodyLimit)
            routing {
                post("/echo") {
                    handlerCalled.set(true)
                    call.respondText(call.receiveText().length.toString())
                }
                // swallows receive failures like the real routes do
                post("/swallow") {
                    handlerCalled.set(true)
                    try {
                        call.receiveText()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", "Invalid request body"))
                        return@post
                    }
                    call.respondText("ok")
                }
            }
        }
    }

    private fun chunked(size: Int) =
        object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.Plain

            override suspend fun writeTo(channel: ByteWriteChannel) {
                val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                var remaining = size
                try {
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size)
                        channel.writeFully(chunk, 0, n)
                        remaining -= n
                    }
                } catch (_: IOException) {
                    // the server stops reading once the limit is hit; the response is what we assert on
                }
            }
        }

    private fun assertPayloadTooLargeBody(text: String) {
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("PAYLOAD_TOO_LARGE", json["error"]?.jsonPrimitive?.content)
        assertEquals("Request body must not exceed 10 MB", json["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `body under the limit passes through`() =
        testApplication {
            val called = AtomicBoolean(false)
            setup(called)
            val response = client.post("/echo") { setBody("hello") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("5", response.bodyAsText())
            assertTrue(called.get())
        }

    @Test
    fun `content-length over the limit gives 413 and the handler does not run`() =
        testApplication {
            val called = AtomicBoolean(false)
            setup(called)
            val response =
                client.post("/echo") {
                    contentType(ContentType.Text.Plain)
                    setBody(ByteArray(overLimit))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertPayloadTooLargeBody(response.bodyAsText())
            assertFalse(called.get())
        }

    @Test
    fun `chunked body over the limit gives 413`() =
        testApplication {
            setup()
            val response = client.post("/echo") { setBody(chunked(overLimit)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertPayloadTooLargeBody(response.bodyAsText())
        }

    @Test
    fun `chunked body over the limit gives 413 even when the handler swallows the failure`() =
        testApplication {
            setup()
            val response = client.post("/swallow") { setBody(chunked(overLimit)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertPayloadTooLargeBody(response.bodyAsText())
        }

    @Test
    fun `chunked body exactly at the limit passes`() =
        testApplication {
            setup()
            val response = client.post("/echo") { setBody(chunked(MAX_REQUEST_BODY_BYTES.toInt())) }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
