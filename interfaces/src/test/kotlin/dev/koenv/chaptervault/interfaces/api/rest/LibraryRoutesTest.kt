package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.extensions.connectors.Connector
import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LibraryRoutesTest {
    private val fakeSeries =
        Series(
            id = Id.from("00000000-0000-0000-0000-000000000001"),
            title = "One Piece",
            connectorId = "mangadex",
            externalId = "ext-001",
            status = SeriesStatus.IN_LIBRARY,
            autoDownload = false,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private val fakeChapter =
        Chapter(
            id = Id.from("00000000-0000-0000-0000-000000000002"),
            seriesId = fakeSeries.id,
            title = "Chapter 1",
            chapterIndex = 1.0,
            externalId = "ch-001",
            downloadStatus = DownloadStatus.PENDING,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private val downloadedChapter = fakeChapter.copy(downloadStatus = DownloadStatus.DOWNLOADED)

    private fun testApp(
        readApi: LibraryReadApi,
        commandApi: LibraryCommandApi,
        taskQueue: TaskQueue = NoOpTaskQueue(),
        fileStorage: ChapterPageSource = NoOpPageSource(),
        connectorRegistry: ConnectorRegistry = StubConnectorRegistry(),
        readingStatusApi: ReadingStatusApi = NoOpReadingStatusApi(),
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
                    libraryRoutes(readApi, commandApi, taskQueue, fileStorage, connectorRegistry, readingStatusApi)
                }
            }
        }
        block()
    }

    // --- page endpoint tests ---

    @Test
    fun `GET chapter page returns 200 with correct Content-Type, ETag, and Cache-Control`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(downloadedChapter)
                },
            commandApi = NoOpCommandApi(),
            fileStorage =
                object : ChapterPageSource {
                    override suspend fun readPage(
                        chapter: Chapter,
                        index: Int,
                    ) = Result.Success(Page(index, byteArrayOf(1, 2, 3), "image/jpeg"))

                    override suspend fun countPages(chapter: Chapter) = Result.Success(1)
                },
        ) {
            val response =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/0") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertContains(response.headers[HttpHeaders.CacheControl]!!, "max-age=86400")
            assertContains(response.headers[HttpHeaders.CacheControl]!!, "immutable")
            val etag = response.headers[HttpHeaders.ETag]
            assertContains(etag!!, "00000000-0000-0000-0000-000000000002")
        }
    }

    @Test
    fun `GET chapter page returns 304 when If-None-Match matches ETag`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(downloadedChapter)
                },
            commandApi = NoOpCommandApi(),
            fileStorage =
                object : ChapterPageSource {
                    override suspend fun readPage(
                        chapter: Chapter,
                        index: Int,
                    ) = Result.Success(Page(index, byteArrayOf(1, 2, 3), "image/jpeg"))

                    override suspend fun countPages(chapter: Chapter) = Result.Success(1)
                },
        ) {
            val firstResponse =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/0") {
                    bearerAuth("user-token")
                }
            val etag = firstResponse.headers[HttpHeaders.ETag]!!

            val secondResponse =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/0") {
                    bearerAuth("user-token")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            assertEquals(HttpStatusCode.NotModified, secondResponse.status)
        }
    }

    @Test
    fun `GET chapter page returns 400 for invalid chapter ID`() {
        testApp(readApi = NoOpReadApi(), commandApi = NoOpCommandApi()) {
            val response =
                client.get("/library/chapters/not-a-uuid/pages/0") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `GET chapter page returns 400 for non-integer page index`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(downloadedChapter)
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/abc") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `GET chapter page returns 404 for unknown chapter`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000099/pages/0") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `GET chapter page returns 423 when chapter is not downloaded`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(fakeChapter) // status = PENDING
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/0") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Locked, response.status)
        }
    }

    @Test
    fun `GET chapter page returns 404 when page index is out of range`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(downloadedChapter)
                },
            commandApi = NoOpCommandApi(),
            fileStorage =
                object : ChapterPageSource {
                    override suspend fun readPage(
                        chapter: Chapter,
                        index: Int,
                    ) = Result.Failure(AppError.NotFound("Page", index.toString()))

                    override suspend fun countPages(chapter: Chapter) = Result.Success(0)
                },
        ) {
            val response =
                client.get("/library/chapters/00000000-0000-0000-0000-000000000002/pages/999") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // --- existing tests ---

    @Test
    fun `GET library series returns 200 with paginated list`() {
        testApp(
            readApi =
                object : LibraryReadApi {
                    override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))

                    override suspend fun listSeries(request: PageRequest) = Result.Success(Pagination(listOf(fakeSeries), 0, 20, 1L))

                    override suspend fun searchLibrary(
                        query: String,
                        request: PageRequest,
                    ) = Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))

                    override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))

                    override suspend fun listChapters(seriesId: Id) = Result.Success(emptyList<Chapter>())

                    override suspend fun listChaptersByStatus(
                        seriesId: Id,
                        status: DownloadStatus,
                    ) = Result.Success(emptyList<Chapter>())

                    override suspend fun inLibraryExternalIds(
                        connectorId: String,
                        externalIds: List<String>,
                    ) = Result.Success(emptySet<String>())
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "One Piece")
            assertContains(body, "totalItems")
        }
    }

    @Test
    fun `GET library series by id returns 200`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "One Piece")
        }
    }

    @Test
    fun `GET library series by id returns 404 when not found`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series/00000000-0000-0000-0000-000000000099") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST library series returns 201 with created series`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Success(fakeSeries)

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Success(fakeSeries)
                },
        ) {
            val response =
                client.post("/library/series") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            assertContains(response.bodyAsText(), "One Piece")
        }
    }

    @Test
    fun `POST library series returns 409 on conflict`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.Conflict("Already in library"))

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Success(fakeSeries)
                },
        ) {
            val response =
                client.post("/library/series") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `POST library series enqueues FETCH_SERIES_METADATA task after success`() {
        val capturingQueue = CapturingTaskQueue()
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Success(fakeSeries)

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Success(fakeSeries)
                },
            taskQueue = capturingQueue,
        ) {
            client.post("/library/series") {
                bearerAuth("admin-token")
                contentType(ContentType.Application.Json)
                setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
            }
            assertEquals(1, capturingQueue.enqueuedTasks.size)
            val task = capturingQueue.enqueuedTasks.first()
            assertEquals(TaskType.FETCH_SERIES_METADATA, task.type)
            assertEquals(fakeSeries.id, task.targetId)
            assertEquals("mangadex", task.payload["connectorId"])
            assertEquals("ext-001", task.payload["externalId"])
        }
    }

    @Test
    fun `GET library series chapters returns 200 with chapter list`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun listChapters(seriesId: Id) = Result.Success(listOf(fakeChapter))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series/00000000-0000-0000-0000-000000000001/chapters") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "Chapter 1")
        }
    }

    // RBAC tests: USER role should get 403 on write endpoints

    @Test
    fun `POST library series returns 403 for USER role`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.post("/library/series") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `DELETE library series returns 403 for USER role`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.delete("/library/series/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `PATCH library series returns 403 for USER role`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.patch("/library/series/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"autoDownload":true}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // DELETE tests

    @Test
    fun `DELETE library series returns 204 when ADMIN deletes existing series`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Failure(AppError.InternalError("not implemented"))
                },
        ) {
            val response =
                client.delete("/library/series/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `DELETE library series returns 404 when series not found`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Failure(AppError.InternalError("not implemented"))
                },
        ) {
            val response =
                client.delete("/library/series/00000000-0000-0000-0000-000000000099") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // Search tests

    @Test
    fun `GET library series search returns 200 with matching results`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun searchLibrary(
                        query: String,
                        request: PageRequest,
                    ) = Result.Success(Pagination(listOf(fakeSeries), 0, 20, 1L))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series/search?q=One") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "One Piece")
            assertContains(response.bodyAsText(), "totalItems")
        }
    }

    // Chapters-by-status tests

    @Test
    fun `GET library chapters with status filter returns only matching chapters`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun listChaptersByStatus(
                        seriesId: Id,
                        status: DownloadStatus,
                    ) = Result.Success(listOf(fakeChapter))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.get("/library/series/00000000-0000-0000-0000-000000000001/chapters?status=PENDING") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "Chapter 1")
        }
    }

    @Test
    fun `GET library chapters with invalid status returns 400`() {
        testApp(readApi = NoOpReadApi(), commandApi = NoOpCommandApi()) {
            val response =
                client.get("/library/series/00000000-0000-0000-0000-000000000001/chapters?status=BOGUS") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // Redownload tests

    @Test
    fun `POST chapter redownload returns 202 and enqueues DOWNLOAD_CHAPTER task`() {
        val capturingQueue = CapturingTaskQueue()
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Success(downloadedChapter)

                    override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)
                },
            commandApi = NoOpCommandApi(),
            taskQueue = capturingQueue,
        ) {
            val response =
                client.post("/library/chapters/00000000-0000-0000-0000-000000000002/redownload") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, capturingQueue.enqueuedTasks.size)
            assertEquals(TaskType.DOWNLOAD_CHAPTER, capturingQueue.enqueuedTasks.first().type)
        }
    }

    @Test
    fun `POST chapter redownload returns 404 for unknown chapter`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.post("/library/chapters/00000000-0000-0000-0000-000000000099/redownload") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST chapter redownload returns 403 for USER role`() {
        testApp(readApi = NoOpReadApi(), commandApi = NoOpCommandApi()) {
            val response =
                client.post("/library/chapters/00000000-0000-0000-0000-000000000002/redownload") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // Delete chapter tests

    @Test
    fun `DELETE library chapter returns 204 when ADMIN deletes existing chapter`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun evictChapter(id: Id) = Result.Success(Unit)

                    override suspend fun markChapterPending(id: Id) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Failure(AppError.InternalError("not implemented"))
                },
        ) {
            val response =
                client.delete("/library/chapters/00000000-0000-0000-0000-000000000002") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `DELETE library chapter returns 404 when chapter not found`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun evictChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))

                    override suspend fun markChapterPending(id: Id) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Failure(AppError.InternalError("not implemented"))
                },
        ) {
            val response =
                client.delete("/library/chapters/00000000-0000-0000-0000-000000000099") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE library chapter returns 403 for USER role`() {
        testApp(readApi = NoOpReadApi(), commandApi = NoOpCommandApi()) {
            val response =
                client.delete("/library/chapters/00000000-0000-0000-0000-000000000002") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // Refresh tests

    @Test
    fun `POST series refresh returns 202 and enqueues FETCH_SERIES_METADATA task`() {
        val capturingQueue = CapturingTaskQueue()
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)
                },
            commandApi = NoOpCommandApi(),
            taskQueue = capturingQueue,
        ) {
            val response =
                client.post("/library/series/00000000-0000-0000-0000-000000000001/refresh") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, capturingQueue.enqueuedTasks.size)
            assertEquals(TaskType.FETCH_SERIES_METADATA, capturingQueue.enqueuedTasks.first().type)
            assertEquals(fakeSeries.id, capturingQueue.enqueuedTasks.first().targetId)
        }
    }

    @Test
    fun `POST series refresh returns 404 when series not found`() {
        testApp(
            readApi =
                object : NoOpReadApi() {
                    override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))
                },
            commandApi = NoOpCommandApi(),
        ) {
            val response =
                client.post("/library/series/00000000-0000-0000-0000-000000000099/refresh") {
                    bearerAuth("admin-token")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST series refresh returns 403 for USER role`() {
        testApp(readApi = NoOpReadApi(), commandApi = NoOpCommandApi()) {
            val response =
                client.post("/library/series/00000000-0000-0000-0000-000000000001/refresh") {
                    bearerAuth("user-token")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // PATCH tests

    @Test
    fun `PATCH library series returns 200 when ADMIN patches existing series`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Success(fakeSeries)
                },
        ) {
            val response =
                client.patch("/library/series/00000000-0000-0000-0000-000000000001") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"autoDownload":true}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "One Piece")
        }
    }

    @Test
    fun `PATCH library series returns 404 when series not found`() {
        testApp(
            readApi = NoOpReadApi(),
            commandApi =
                object : LibraryCommandApi {
                    override suspend fun addToLibrary(
                        connectorId: String,
                        externalId: String,
                        language: String,
                        autoDownload: Boolean,
                    ) = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun removeSeries(id: Id) = Result.Success(Unit)

                    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun markChapterPending(id: Id): Result<Unit> =
                        Result.Failure(AppError.InternalError("not implemented"))

                    override suspend fun updateSeries(
                        id: Id,
                        autoDownload: Boolean?,
                        defaultFormat: ChapterFormat?,
                    ) = Result.Failure(AppError.NotFound("Series", id.toString()))
                },
        ) {
            val response =
                client.patch("/library/series/00000000-0000-0000-0000-000000000099") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"autoDownload":true}""")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}

private open class NoOpReadApi : LibraryReadApi {
    override suspend fun getSeries(id: Id): Result<Series> = Result.Failure(AppError.NotFound("Series", id.toString()))

    override suspend fun listSeries(request: PageRequest) = Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))

    override suspend fun searchLibrary(
        query: String,
        request: PageRequest,
    ) = Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))

    override suspend fun getChapter(id: Id): Result<Chapter> = Result.Failure(AppError.NotFound("Chapter", id.toString()))

    override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> = Result.Success(emptyList<Chapter>())

    override suspend fun listChaptersByStatus(
        seriesId: Id,
        status: DownloadStatus,
    ): Result<List<Chapter>> = Result.Success(emptyList<Chapter>())

    override suspend fun inLibraryExternalIds(
        connectorId: String,
        externalIds: List<String>,
    ): Result<Set<String>> = Result.Success(emptySet())
}

private class NoOpCommandApi : LibraryCommandApi {
    override suspend fun addToLibrary(
        connectorId: String,
        externalId: String,
        language: String,
        autoDownload: Boolean,
    ) = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun removeSeries(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun evictChapter(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun markChapterPending(id: Id): Result<Unit> = Result.Failure(AppError.InternalError("not implemented"))

    override suspend fun updateSeries(
        id: Id,
        autoDownload: Boolean?,
        defaultFormat: ChapterFormat?,
    ) = Result.Failure(AppError.InternalError("not implemented"))
}

private class NoOpPageSource : ChapterPageSource {
    override suspend fun readPage(
        chapter: Chapter,
        index: Int,
    ): Result<Page> = Result.Failure(AppError.NotFound("Page", index.toString()))

    override suspend fun countPages(chapter: Chapter): Result<Int> = Result.Failure(AppError.NotFound("Chapter", chapter.id.toString()))
}

private class NoOpTaskQueue : TaskQueue {
    override suspend fun enqueue(task: Task): Result<Id> = Result.Success(task.id)

    override suspend fun dequeue(): Task? = null

    override suspend fun cancel(taskId: Id): Result<Unit> = Result.Success(Unit)

    override suspend fun getTask(taskId: Id): Task? = null
}

private class CapturingTaskQueue : TaskQueue {
    val enqueuedTasks = mutableListOf<Task>()

    override suspend fun enqueue(task: Task): Result<Id> {
        enqueuedTasks.add(task)
        return Result.Success(task.id)
    }

    override suspend fun dequeue(): Task? = null

    override suspend fun cancel(taskId: Id): Result<Unit> = Result.Success(Unit)

    override suspend fun getTask(taskId: Id): Task? = null
}

/** Returns a stub connector that supports "en" for any requested connectorId. */
private class StubConnectorRegistry : ConnectorRegistry {
    override fun register(connector: Connector) = Unit

    override fun findById(id: String): Connector? = StubConnector(id)

    override fun all(): List<Connector> = emptyList()
}

private class NoOpReadingStatusApi : ReadingStatusApi {
    override suspend fun setStatus(
        userId: Id,
        seriesId: Id,
        status: ReadingStatus,
    ) = dev.koenv.chaptervault.shared.result.Result
        .Success(Unit)

    override suspend fun clearStatus(
        userId: Id,
        seriesId: Id,
    ) = dev.koenv.chaptervault.shared.result.Result
        .Success(Unit)

    override suspend fun getStatus(
        userId: Id,
        seriesId: Id,
    ): ReadingStatus? = null
}

private class StubConnector(
    override val id: String,
) : Connector {
    override val name: String = id

    override suspend fun search(
        query: String,
        request: PageRequest,
    ) = Result.Failure(AppError.InternalError("stub"))

    override suspend fun fetchSeries(externalId: String) = Result.Failure(AppError.InternalError("stub"))

    override suspend fun fetchChapters(
        externalId: String,
        language: String,
    ) = Result.Failure(AppError.InternalError("stub"))

    override suspend fun download(
        chapter: Chapter,
        format: ChapterFormat,
    ) = Result.Failure(AppError.InternalError("stub"))

    override fun supportedLanguages(): List<String> = listOf("en")
}
