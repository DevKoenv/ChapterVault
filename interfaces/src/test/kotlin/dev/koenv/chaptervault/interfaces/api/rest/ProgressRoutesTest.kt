package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadProgress
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProgressRoutesTest {
    private val userId = Id.from("00000000-0000-0000-0000-000000000010")
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000002")

    private fun testApp(
        progressApi: ProgressApi,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
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
            routing {
                authenticate("auth-bearer") { progressRoutes(progressApi) }
            }
        }
        block()
    }

    @Test
    fun `GET progress returns 200 with readCount and totalCount`() {
        testApp(
            progressApi =
                object : NoOpProgressApi() {
                    override suspend fun getProgress(
                        userId: Id,
                        seriesId: Id,
                    ) = Result.Success(ReadProgress(seriesId, readCount = 3, totalCount = 10))
                },
        ) {
            val response = client.get("/library/series/$seriesId/progress") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "readCount")
            assertContains(response.bodyAsText(), "totalCount")
        }
    }

    @Test
    fun `GET progress returns 400 for invalid series ID`() {
        testApp(progressApi = NoOpProgressApi()) {
            val response = client.get("/library/series/not-a-uuid/progress") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `GET progress returns 404 when series not found`() {
        testApp(
            progressApi =
                object : NoOpProgressApi() {
                    override suspend fun getProgress(
                        userId: Id,
                        seriesId: Id,
                    ) = Result.Failure(AppError.NotFound("Series", seriesId.toString()))
                },
        ) {
            val response = client.get("/library/series/$seriesId/progress") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST read marks chapter as read and returns 204`() {
        testApp(progressApi = NoOpProgressApi()) {
            val response = client.post("/library/chapters/$chapterId/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `POST read returns 400 for invalid chapter ID`() {
        testApp(progressApi = NoOpProgressApi()) {
            val response = client.post("/library/chapters/not-a-uuid/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST read returns 404 when chapter not found`() {
        testApp(
            progressApi =
                object : NoOpProgressApi() {
                    override suspend fun markRead(
                        userId: Id,
                        chapterId: Id,
                    ) = Result.Failure(AppError.NotFound("Chapter", chapterId.toString()))
                },
        ) {
            val response = client.post("/library/chapters/$chapterId/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE read unmarks chapter and returns 204`() {
        testApp(progressApi = NoOpProgressApi()) {
            val response = client.delete("/library/chapters/$chapterId/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `DELETE read returns 400 for invalid chapter ID`() {
        testApp(progressApi = NoOpProgressApi()) {
            val response = client.delete("/library/chapters/not-a-uuid/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `DELETE read returns 404 when chapter not found`() {
        testApp(
            progressApi =
                object : NoOpProgressApi() {
                    override suspend fun markUnread(
                        userId: Id,
                        chapterId: Id,
                    ) = Result.Failure(AppError.NotFound("Chapter", chapterId.toString()))
                },
        ) {
            val response = client.delete("/library/chapters/$chapterId/read") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}

private open class NoOpProgressApi : ProgressApi {
    override suspend fun markRead(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun markUnread(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> = Result.Success(Unit)

    override suspend fun getProgress(
        userId: Id,
        seriesId: Id,
    ): Result<ReadProgress> = Result.Failure(AppError.NotFound("Series", seriesId.toString()))
}
