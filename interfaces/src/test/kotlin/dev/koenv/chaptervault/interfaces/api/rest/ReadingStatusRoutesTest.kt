package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ReadingStatusRoutesTest {
    private val userId = Id.from("00000000-0000-0000-0000-000000000010")
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    bearer("auth-bearer") {
                        authenticate { cred ->
                            if (cred.token == "user-token") {
                                KtorPrincipal(UserPrincipal(userId, "user", setOf(Role.USER)))
                            } else {
                                null
                            }
                        }
                    }
                }
                routing { authenticate("auth-bearer") { readingStatusRoutes(StubReadingStatusApi()) } }
            }
            block()
        }

    @Test
    fun `PUT status returns 204`() =
        testApp {
            val res =
                client.put("/library/series/$seriesId/status") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"READING"}""")
                }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }

    @Test
    fun `PUT status returns 400 for unknown status value`() =
        testApp {
            val res =
                client.put("/library/series/$seriesId/status") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"INVALID"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }

    @Test
    fun `DELETE status returns 204`() =
        testApp {
            val res = client.delete("/library/series/$seriesId/status") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }

    @Nested
    inner class NullPrincipal {
        private fun testAppNoAuth(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { readingStatusRoutes(StubReadingStatusApi()) }
                }
                block()
            }

        @Test
        fun `PUT status returns 403 when principal is missing`() =
            testAppNoAuth {
                val res =
                    client.put("/library/series/$seriesId/status") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"READING"}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, res.status)
            }

        @Test
        fun `DELETE status returns 403 when principal is missing`() =
            testAppNoAuth {
                assertEquals(HttpStatusCode.Forbidden, client.delete("/library/series/$seriesId/status").status)
            }
    }
}

private class StubReadingStatusApi : ReadingStatusApi {
    override suspend fun setStatus(
        userId: Id,
        seriesId: Id,
        status: ReadingStatus,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun clearStatus(
        userId: Id,
        seriesId: Id,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun getStatus(
        userId: Id,
        seriesId: Id,
    ): ReadingStatus? = null
}
