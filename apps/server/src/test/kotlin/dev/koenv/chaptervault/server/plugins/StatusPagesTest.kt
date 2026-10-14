package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StatusPagesTest {
    private fun ApplicationTestBuilder.setup() {
        application {
            configureStatusPages()
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate {
                        if (it.token ==
                            "good"
                        ) {
                            KtorPrincipal(UserPrincipal(Id.generate(), "alice", setOf(Role.USER)))
                        } else {
                            null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    get("/secure") { call.respond(HttpStatusCode.OK, "fine") }
                    get("/typed-401") {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            dev.koenv.chaptervault.interfaces.serialization.dto.v1
                                .ErrorResponse("UNAUTHORIZED", "Current password is incorrect"),
                        )
                    }
                    get("/missing") {
                        call.respond(
                            HttpStatusCode.NotFound,
                            dev.koenv.chaptervault.interfaces.serialization.dto.v1
                                .ErrorResponse("NOT_FOUND", "Series 'abc' not found"),
                        )
                    }
                    get("/boom") { error("secret internals") }
                }
            }
        }
    }

    private fun code(body: String) =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content

    @Test
    fun `a rejected bearer token gets the standard JSON error body`() =
        testApplication {
            setup()

            val response = client.get("/secure") { bearerAuth("bad") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("UNAUTHORIZED", code(response.bodyAsText()))
            assertNotNull(response.headers[HttpHeaders.WWWAuthenticate], "the challenge header must survive")
        }

    @Test
    fun `a missing bearer token gets the standard JSON error body`() =
        testApplication {
            setup()

            val response = client.get("/secure")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHORIZED", code(response.bodyAsText()))
        }

    @Test
    fun `a 401 a route produced itself keeps its own message`() =
        testApplication {
            setup()

            val response = client.get("/typed-401") { bearerAuth("good") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                "Current password is incorrect",
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["message"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `a 404 a route produced itself keeps its own message`() =
        testApplication {
            setup()

            val response = client.get("/missing") { bearerAuth("good") }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(
                "Series 'abc' not found",
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["message"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `an unknown route gets the standard JSON error body`() =
        testApplication {
            setup()

            val response = client.get("/nope") { bearerAuth("good") }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", code(response.bodyAsText()))
        }

    @Test
    fun `an unhandled exception answers 500 without leaking its message`() =
        testApplication {
            setup()

            val response = client.get("/boom") { bearerAuth("good") }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL_ERROR", code(response.bodyAsText()))
            assertEquals(false, response.bodyAsText().contains("secret internals"))
        }
}
