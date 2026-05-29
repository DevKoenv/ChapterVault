package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.Bookmark
import dev.koenv.chaptervault.kernel.api.BookmarkApi
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

class BookmarkRoutesTest {
    private val userId = Id.from("00000000-0000-0000-0000-000000000010")
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000002")
    private val bookmarkId = Id.from("00000000-0000-0000-0000-000000000003")

    private val fakeBookmark = Bookmark(id = bookmarkId, chapterId = chapterId, page = 5, createdAt = Instant.EPOCH)

    private fun testApp(
        bookmarkApi: BookmarkApi,
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
                authenticate("auth-bearer") { bookmarkRoutes(bookmarkApi) }
            }
        }
        block()
    }

    @Test
    fun `GET bookmarks returns 200 with bookmark list`() {
        testApp(
            bookmarkApi =
                object : NoOpBookmarkApi() {
                    override suspend fun list(
                        userId: Id,
                        seriesId: Id,
                    ) = Result.Success(listOf(fakeBookmark))
                },
        ) {
            val response = client.get("/library/series/$seriesId/bookmarks") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), bookmarkId.toString())
        }
    }

    @Test
    fun `GET bookmarks returns 400 for invalid series ID`() {
        testApp(bookmarkApi = NoOpBookmarkApi()) {
            val response = client.get("/library/series/not-a-uuid/bookmarks") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST bookmark creates bookmark and returns 201`() {
        testApp(
            bookmarkApi =
                object : NoOpBookmarkApi() {
                    override suspend fun create(
                        userId: Id,
                        chapterId: Id,
                        page: Int,
                    ) = Result.Success(fakeBookmark)
                },
        ) {
            val response =
                client.post("/library/chapters/$chapterId/bookmarks") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"page":5}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            assertContains(response.bodyAsText(), bookmarkId.toString())
        }
    }

    @Test
    fun `POST bookmark returns 400 for invalid chapter ID`() {
        testApp(bookmarkApi = NoOpBookmarkApi()) {
            val response =
                client.post("/library/chapters/not-a-uuid/bookmarks") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"page":5}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST bookmark returns 400 for invalid request body`() {
        testApp(bookmarkApi = NoOpBookmarkApi()) {
            val response =
                client.post("/library/chapters/$chapterId/bookmarks") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST bookmark returns 404 when chapter not found`() {
        testApp(
            bookmarkApi =
                object : NoOpBookmarkApi() {
                    override suspend fun create(
                        userId: Id,
                        chapterId: Id,
                        page: Int,
                    ) = Result.Failure(AppError.NotFound("Chapter", chapterId.toString()))
                },
        ) {
            val response =
                client.post("/library/chapters/$chapterId/bookmarks") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"page":5}""")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE bookmark returns 204 on success`() {
        testApp(bookmarkApi = NoOpBookmarkApi()) {
            val response = client.delete("/library/bookmarks/$bookmarkId") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `DELETE bookmark returns 404 when not found`() {
        testApp(
            bookmarkApi =
                object : NoOpBookmarkApi() {
                    override suspend fun delete(
                        userId: Id,
                        bookmarkId: Id,
                    ) = Result.Failure(AppError.NotFound("Bookmark", bookmarkId.toString()))
                },
        ) {
            val response = client.delete("/library/bookmarks/$bookmarkId") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE bookmark returns 400 for invalid bookmark ID`() {
        testApp(bookmarkApi = NoOpBookmarkApi()) {
            val response = client.delete("/library/bookmarks/not-a-uuid") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}

private open class NoOpBookmarkApi : BookmarkApi {
    override suspend fun create(
        userId: Id,
        chapterId: Id,
        page: Int,
    ): Result<Bookmark> = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun list(
        userId: Id,
        seriesId: Id,
    ): Result<List<Bookmark>> = Result.Success(emptyList())

    override suspend fun delete(
        userId: Id,
        bookmarkId: Id,
    ): Result<Unit> = Result.Success(Unit)
}
