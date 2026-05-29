package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.kernel.api.NotificationType
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

class NotificationRoutesTest {
    private val targetId = Id.from("00000000-0000-0000-0000-000000000001")

    private val fakeTarget =
        NotificationTarget(
            id = targetId,
            name = "My Ntfy",
            type = NotificationType.NTFY,
            url = "https://ntfy.sh/mychannel",
            token = null,
            enabled = true,
            createdAt = Instant.EPOCH,
        )

    private fun testApp(
        notificationApi: NotificationApi = NoOpNotificationApi(),
        dispatchApi: NotificationDispatchApi = NoOpDispatchApi(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        when (cred.token) {
                            "admin-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN)))
                            "user-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    notificationRoutes(notificationApi, dispatchApi)
                }
            }
        }
        block()
    }

    @Test
    fun `GET notifications returns 200 with targets for authenticated user`() {
        testApp(
            notificationApi =
                object : NoOpNotificationApi() {
                    override suspend fun listTargets() = listOf(fakeTarget)
                },
        ) {
            val response = client.get("/notifications") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "My Ntfy")
        }
    }

    @Test
    fun `GET notifications returns 401 without auth`() {
        testApp {
            val response = client.get("/notifications")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST notifications returns 201 for ADMIN`() {
        testApp(
            notificationApi =
                object : NoOpNotificationApi() {
                    override suspend fun createTarget(input: NotificationTargetInput) = Result.Success(fakeTarget)
                },
        ) {
            val response =
                client.post("/notifications") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"My Ntfy","type":"NTFY","url":"https://ntfy.sh/mychannel"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            assertContains(response.bodyAsText(), "My Ntfy")
        }
    }

    @Test
    fun `POST notifications returns 403 for non-ADMIN user`() {
        testApp {
            val response =
                client.post("/notifications") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"My Ntfy","type":"NTFY","url":"https://ntfy.sh/mychannel"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `POST notifications test dispatch returns 200 for ADMIN`() {
        testApp(
            dispatchApi =
                object : NoOpDispatchApi() {
                    override suspend fun sendTest(targetId: Id) = Result.Success(Unit)
                },
        ) {
            val response =
                client.post("/notifications/$targetId/test") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `DELETE notifications returns 204 for ADMIN`() {
        testApp(
            notificationApi =
                object : NoOpNotificationApi() {
                    override suspend fun deleteTarget(id: Id) = Result.Success(Unit)
                },
        ) {
            val response =
                client.delete("/notifications/$targetId") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }
}

private open class NoOpNotificationApi : NotificationApi {
    override suspend fun listTargets(): List<NotificationTarget> = emptyList()

    override suspend fun findTarget(id: Id): Result<NotificationTarget> =
        Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))

    override suspend fun createTarget(input: NotificationTargetInput): Result<NotificationTarget> =
        Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun updateTarget(
        id: Id,
        patch: NotificationTargetPatch,
    ): Result<NotificationTarget> = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun deleteTarget(id: Id): Result<Unit> = Result.Success(Unit)
}

private open class NoOpDispatchApi : NotificationDispatchApi {
    override suspend fun sendTest(targetId: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))
}
