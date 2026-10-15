package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.ChapterWithSeries
import dev.koenv.chaptervault.kernel.api.GenreCount
import dev.koenv.chaptervault.kernel.api.LanguageCount
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.RecentlyReadEntry
import dev.koenv.chaptervault.kernel.api.SeriesQuery
import dev.koenv.chaptervault.kernel.api.SeriesSort
import dev.koenv.chaptervault.kernel.api.StatusCount
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpdsRoutesTest {
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000002")
    private val downloadedChapterId = Id.from("00000000-0000-0000-0000-000000000003")

    private val fakeSeries =
        Series(
            id = seriesId,
            title = "Test Series",
            connectorId = "mock",
            externalId = "ext-1",
            language = "en",
            status = SeriesStatus.IN_LIBRARY,
            autoDownload = false,
            defaultFormat = null,
            coverUrl = null,
            description = "A test series",
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private val availableChapter =
        Chapter(
            id = chapterId,
            seriesId = seriesId,
            title = "Chapter 1",
            chapterIndex = 1.0,
            externalId = "ext-ch-1",
            downloadStatus = DownloadStatus.AVAILABLE,
            pageCount = null,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private val downloadedChapter =
        Chapter(
            id = downloadedChapterId,
            seriesId = seriesId,
            title = "Chapter 2",
            chapterIndex = 2.0,
            externalId = "ext-ch-2",
            downloadStatus = DownloadStatus.DOWNLOADED,
            pageCount = 5,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private val fakePageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val fakePageMime = "image/png"

    private val fakeLibraryRead =
        object : LibraryReadApi {
            override suspend fun getSeries(id: Id) =
                if (id == seriesId) {
                    Result.Success(fakeSeries)
                } else {
                    Result.Failure(AppError.NotFound("Series", id.toString()))
                }

            override suspend fun listSeries(
                query: SeriesQuery,
                request: PageRequest,
            ) = Result.Success(
                Pagination(items = listOf(fakeSeries), page = 0, size = 20, totalItems = 1L),
            )

            override suspend fun searchLibrary(
                query: String,
                request: PageRequest,
            ) = Result.Success(
                Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L),
            )

            override suspend fun getChapter(id: Id) =
                when (id) {
                    chapterId -> Result.Success(availableChapter)
                    downloadedChapterId -> Result.Success(downloadedChapter)
                    else -> Result.Failure(AppError.NotFound("Chapter", id.toString()))
                }

            override suspend fun listChapters(seriesId: Id) =
                if (seriesId == this@OpdsRoutesTest.seriesId) {
                    Result.Success(listOf(availableChapter, downloadedChapter))
                } else {
                    Result.Failure(AppError.NotFound("Series", seriesId.toString()))
                }

            override suspend fun listChaptersByStatus(
                seriesId: Id,
                status: DownloadStatus,
            ) = Result.Success(emptyList<Chapter>())

            override suspend fun inLibraryExternalIds(
                connectorId: String,
                externalIds: List<String>,
            ) = Result.Success(emptySet<String>())

            override suspend fun listSeriesByIds(
                ids: Collection<Id>,
                request: PageRequest,
            ) = Result.Success(Pagination(emptyList<Series>(), request.page, request.size, 0L))

            override suspend fun listChaptersPaginated(
                seriesId: Id,
                status: DownloadStatus?,
                request: PageRequest,
            ) = Result.Success(Pagination(emptyList<Chapter>(), request.page, request.size, 0L))
        }

    private val fakeOpdsRead =
        object : OpdsReadApi {
            override suspend fun listReadHistory(
                userId: Id,
                request: PageRequest,
            ) = Result.Success(Pagination(items = emptyList<RecentlyReadEntry>(), page = 0, size = 20, totalItems = 0L))

            override suspend fun listRecentlyAddedChapters(request: PageRequest) =
                Result.Success(Pagination(items = emptyList<ChapterWithSeries>(), page = 0, size = 20, totalItems = 0L))

            override suspend fun listGenres() = Result.Success(emptyList<GenreCount>())

            override suspend fun listLanguages() = Result.Success(emptyList<LanguageCount>())

            override suspend fun listUpstreamStatuses() = Result.Success(emptyList<StatusCount>())
        }

    private val fakePageSource =
        object : ChapterPageSource {
            override suspend fun readPage(
                chapter: Chapter,
                index: Int,
            ): Result<Page> {
                if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) return Result.Failure(AppError.NotFound("Page", index.toString()))
                if (index < 0 || index >= (chapter.pageCount ?: 0)) return Result.Failure(AppError.NotFound("Page", index.toString()))
                return Result.Success(Page(index, fakePageBytes, fakePageMime))
            }

            override suspend fun countPages(chapter: Chapter): Result<Int> {
                if (chapter.downloadStatus !=
                    DownloadStatus.DOWNLOADED
                ) {
                    return Result.Failure(AppError.NotFound("Chapter", chapter.id.toString()))
                }
                return Result.Success(chapter.pageCount ?: 0)
            }
        }

    private fun testAppWith(
        libraryRead: LibraryReadApi,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("auth-basic") {
                    realm = "ChapterVault"
                    validate { cred ->
                        if (cred.name == "user" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        } else if (cred.name == "nobody" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "nobody", emptySet()))
                        } else {
                            null
                        }
                    }
                }
            }
            opdsRoutes(libraryRead, fakeOpdsRead, fakePageSource)
            opdsPageRoutes(libraryRead, fakePageSource)
        }
        block()
    }

    private fun testAppWithOpds(
        opdsRead: OpdsReadApi,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("auth-basic") {
                    realm = "ChapterVault"
                    validate { cred ->
                        if (cred.name == "user" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        } else if (cred.name == "nobody" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "nobody", emptySet()))
                        } else {
                            null
                        }
                    }
                }
            }
            opdsRoutes(fakeLibraryRead, opdsRead, fakePageSource)
            opdsPageRoutes(fakeLibraryRead, fakePageSource)
        }
        block()
    }

    private fun testApp(
        publicUrl: String? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("auth-basic") {
                    realm = "ChapterVault"
                    validate { cred ->
                        if (cred.name == "user" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        } else if (cred.name == "nobody" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "nobody", emptySet()))
                        } else {
                            null
                        }
                    }
                }
                bearer("auth-bearer") {
                    authenticate { cred ->
                        if (cred.token == "valid-token") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        } else {
                            null
                        }
                    }
                }
            }
            opdsRoutes(fakeLibraryRead, fakeOpdsRead, fakePageSource, publicUrl)
            opdsPageRoutes(fakeLibraryRead, fakePageSource)
            routing {
                authenticate("auth-bearer") {
                    get("/library/chapters/{id}/pages/{index}") {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }
        block()
    }

    @Test
    fun `navigation feed returns 200 with atom+xml content type`() =
        testApp {
            val res = client.get("/opds/v1") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("application/atom+xml") == true)
        }

    @Test
    fun `catalog feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/catalog").status)
        }

    @Test
    fun `catalog feed returns 500 with error body on listSeries failure`() {
        val failingLibrary =
            object : LibraryReadApi {
                override suspend fun getSeries(id: Id) = Result.Failure(AppError.InternalError("db error"))

                override suspend fun listSeries(
                    query: SeriesQuery,
                    request: PageRequest,
                ) = Result.Failure(AppError.InternalError("db error"))

                override suspend fun searchLibrary(
                    query: String,
                    request: PageRequest,
                ) = Result.Success(Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L))

                override suspend fun getChapter(id: Id) = Result.Failure(AppError.InternalError("db error"))

                override suspend fun listChapters(seriesId: Id) = Result.Failure(AppError.InternalError("db error"))

                override suspend fun listChaptersByStatus(
                    seriesId: Id,
                    status: DownloadStatus,
                ) = Result.Success(emptyList<Chapter>())

                override suspend fun inLibraryExternalIds(
                    connectorId: String,
                    externalIds: List<String>,
                ) = Result.Success(emptySet<String>())

                override suspend fun listSeriesByIds(
                    ids: Collection<Id>,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Series>(), request.page, request.size, 0L))

                override suspend fun listChaptersPaginated(
                    seriesId: Id,
                    status: DownloadStatus?,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Chapter>(), request.page, request.size, 0L))
            }
        testAppWith(failingLibrary) {
            val res = client.get("/opds/v1/catalog") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    @Test
    fun `catalog feed returns 200 with series entries when authenticated`() =
        testApp {
            val res = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "Test Series")
        }

    @Test
    fun `series feed returns chapter entries and CBZ acquisition link for downloaded chapter`() =
        testApp {
            val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertContains(body, "Chapter 2")
            assertContains(body, "application/x-cbz")
        }

    @Test
    fun `series feed includes PSE link for downloaded chapter with pageCount`() =
        testApp {
            val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
            val body = res.bodyAsText()
            assertContains(body, "rel=\"http://vaemendis.net/opds-pse/stream\"")
            assertContains(body, "pse:count=\"5\"")
            assertContains(body, "xmlns:pse=\"http://vaemendis.net/opds-pse/ns\"")
            assertContains(body, "type=\"image/png\"")
            assertTrue(!body.contains("image/jpeg"), "the pages are PNG, nothing in this feed may claim JPEG")
        }

    @Test
    fun `series feed does not include acquisition or PSE link for undownloaded chapter`() =
        testApp {
            val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
            val body = res.bodyAsText()
            assertContains(body, "Chapter 1")
            assertTrue(!body.contains("/opds/v1/download/$chapterId"), "undownloaded chapter should have no download link")
            assertTrue(
                !body.contains("/opds/v1/chapters/$chapterId/pages/{pageNumber}"),
                "undownloaded chapter should have no PSE link",
            )
        }

    @Test
    fun `series feed returns 404 for unknown series id`() =
        testApp {
            val fakeId = Id.generate()
            val res = client.get("/opds/v1/series/$fakeId") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }

    @Test
    fun `download endpoint returns 404 when route not wired in test scope`() =
        testApp {
            val res = client.get("/opds/v1/download/$downloadedChapterId") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `download endpoint returns 404 for unknown chapter`() =
        testApp {
            val res = client.get("/opds/v1/download/${Id.generate()}") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `PSE page endpoint returns 200 with correct mime type and bytes`() =
        testApp {
            val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(
                fakePageMime,
                res
                    .contentType()
                    ?.toString()
                    ?.substringBefore(";")
                    ?.trim(),
            )
            assertTrue(res.body<ByteArray>().contentEquals(fakePageBytes))
        }

    @Test
    fun `PSE page endpoint returns 404 for out-of-range page index`() =
        testApp {
            val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/99") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }

    @Test
    fun `PSE page endpoint includes ETag and Cache-Control headers`() =
        testApp {
            val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") { basicAuth("user", "pass") }
            assertNotNull(res.headers["ETag"], "Expected ETag header")
            val cacheControl = res.headers["Cache-Control"]
            assertNotNull(cacheControl, "Expected Cache-Control header")
            assertContains(cacheControl!!, "max-age=31536000")
            assertContains(cacheControl, "immutable")
            assertContains(cacheControl, "private")
        }

    @Test
    fun `PSE page endpoint returns 404 for a negative page index`() =
        testApp {
            val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/-1") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `catalog treats a negative page as the first page`() =
        testApp {
            val res = client.get("/opds/v1/catalog?page=-3") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `a user without read permission gets 403 from every OPDS feed and page`() =
        testApp {
            val paths =
                listOf(
                    "/opds/v1",
                    "/opds/v1/catalog",
                    "/opds/v1/history",
                    "/opds/v1/library-updates",
                    "/opds/v1/series/$seriesId",
                    "/opds/v1/chapters/$downloadedChapterId/pages/0",
                    "/opds/v1/search.xml",
                    "/opds/v1/search?q=test",
                )
            paths.forEach { path ->
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { basicAuth("nobody", "pass") }.status, path)
            }
        }

    @Test
    fun `OPDS catalog returns 401 when Bearer token sent instead of Basic Auth`() =
        testApp {
            val res = client.get("/opds/v1/catalog") { bearerAuth("valid-token") }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }

    @Test
    fun `library page route returns 401 when Basic Auth sent instead of Bearer`() =
        testApp {
            val res = client.get("/library/chapters/$chapterId/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }

    // feed structure

    @Test
    fun `navigation feed contains self link start link and catalog subsection link`() {
        testApp {
            val body = client.get("/opds/v1") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "rel=\"self\"")
            assertContains(body, "rel=\"start\"")
            assertContains(body, "rel=\"subsection\"")
            assertContains(body, "/opds/v1/catalog")
            assertContains(body, "/opds/v1/history")
            assertContains(body, "/opds/v1/library-updates")
            assertContains(body, "/opds/v1/library/genres")
            assertContains(body, "/opds/v1/library/languages")
            assertContains(body, "/opds/v1/library/status")
        }
    }

    @Test
    fun `catalog feed includes OpenSearch total results and items per page`() {
        testApp {
            val body = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "http://a9.com/-/spec/opensearch/1.1/")
            assertContains(body, ">1<", message = "expected totalResults=1")
            assertContains(body, ">20<", message = "expected itemsPerPage=20")
        }
    }

    @Test
    fun `catalog feed returns 200 with no entries when library is empty`() {
        val emptyLibrary =
            object : LibraryReadApi {
                override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))

                override suspend fun listSeries(
                    query: SeriesQuery,
                    request: PageRequest,
                ) = Result.Success(Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L))

                override suspend fun searchLibrary(
                    query: String,
                    request: PageRequest,
                ) = Result.Success(Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L))

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

                override suspend fun listSeriesByIds(
                    ids: Collection<Id>,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Series>(), request.page, request.size, 0L))

                override suspend fun listChaptersPaginated(
                    seriesId: Id,
                    status: DownloadStatus?,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Chapter>(), request.page, request.size, 0L))
            }
        testAppWith(emptyLibrary) {
            val res = client.get("/opds/v1/catalog") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertContains(body, "http://a9.com/-/spec/opensearch/1.1/")
            assertTrue(!body.contains("<entry>"), "expected no entries in empty catalog")
        }
    }

    @Test
    fun `catalog feed includes next link when total items exceed page size`() {
        val paginatedLibrary =
            object : LibraryReadApi {
                override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)

                override suspend fun listSeries(
                    query: SeriesQuery,
                    request: PageRequest,
                ) = Result.Success(
                    Pagination(items = List(request.size) { fakeSeries }, page = request.page, size = request.size, totalItems = 25L),
                )

                override suspend fun searchLibrary(
                    query: String,
                    request: PageRequest,
                ) = Result.Success(Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L))

                override suspend fun getChapter(id: Id) = fakeLibraryRead.getChapter(id)

                override suspend fun listChapters(seriesId: Id) = fakeLibraryRead.listChapters(seriesId)

                override suspend fun listChaptersByStatus(
                    seriesId: Id,
                    status: DownloadStatus,
                ) = Result.Success(emptyList<Chapter>())

                override suspend fun inLibraryExternalIds(
                    connectorId: String,
                    externalIds: List<String>,
                ) = Result.Success(emptySet<String>())

                override suspend fun listSeriesByIds(
                    ids: Collection<Id>,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Series>(), request.page, request.size, 0L))

                override suspend fun listChaptersPaginated(
                    seriesId: Id,
                    status: DownloadStatus?,
                    request: PageRequest,
                ) = Result.Success(Pagination(emptyList<Chapter>(), request.page, request.size, 0L))
            }
        testAppWith(paginatedLibrary) {
            val res = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "rel=\"next\"")
        }
    }

    // input validation

    @Test
    fun `series feed returns 400 for malformed series ID`() {
        testApp {
            val res = client.get("/opds/v1/series/not-a-uuid") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    @Test
    fun `PSE page endpoint returns 400 for malformed chapter ID`() {
        testApp {
            val res = client.get("/opds/v1/chapters/not-a-uuid/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    // page access edge cases

    @Test
    fun `PSE page endpoint returns 404 for non-downloaded chapter`() {
        testApp {
            val res = client.get("/opds/v1/chapters/$chapterId/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    @Test
    fun `PSE page endpoint returns 404 for unknown chapter ID`() {
        testApp {
            val res = client.get("/opds/v1/chapters/${Id.generate()}/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            val body = res.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    // caching

    @Test
    fun `PSE page endpoint returns 304 when If-None-Match matches ETag`() {
        testApp {
            val first = client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") { basicAuth("user", "pass") }
            val etag = first.headers[HttpHeaders.ETag]!!
            val second =
                client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") {
                    basicAuth("user", "pass")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            assertEquals(HttpStatusCode.NotModified, second.status)
        }
    }

    // content-type negotiation

    @Test
    fun `navigation feed returns text-xml content type when Accept header is text-html`() {
        testApp {
            val res =
                client.get("/opds/v1") {
                    basicAuth("user", "pass")
                    header(HttpHeaders.Accept, "text/html")
                }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("text/xml") == true)
        }
    }

    // history feed

    @Test
    fun `history feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/history").status)
        }

    @Test
    fun `history feed returns 200 with atom+xml content type`() =
        testApp {
            val res = client.get("/opds/v1/history") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("application/atom+xml") == true)
        }

    @Test
    fun `history feed includes series title from read history`() {
        val opdsWithHistory =
            object : OpdsReadApi by fakeOpdsRead {
                override suspend fun listReadHistory(
                    userId: Id,
                    request: PageRequest,
                ) = Result.Success(
                    Pagination(
                        items =
                            listOf(
                                RecentlyReadEntry(
                                    series = fakeSeries,
                                    lastReadAt = Instant.EPOCH,
                                    chaptersRead = 3,
                                    totalChapters = 10,
                                ),
                            ),
                        page = 0,
                        size = 20,
                        totalItems = 1L,
                    ),
                )
            }
        testAppWithOpds(opdsWithHistory) {
            val body = client.get("/opds/v1/history") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "Test Series")
            assertContains(body, "3")
            assertContains(body, "10")
        }
    }

    // library-updates feed

    @Test
    fun `library-updates feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/library-updates").status)
        }

    @Test
    fun `library-updates feed returns 200 with atom+xml content type`() =
        testApp {
            val res = client.get("/opds/v1/library-updates") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("application/atom+xml") == true)
        }

    @Test
    fun `library-updates feed includes chapter title`() {
        val opdsWithChapters =
            object : OpdsReadApi by fakeOpdsRead {
                override suspend fun listRecentlyAddedChapters(request: PageRequest) =
                    Result.Success(
                        Pagination(
                            items = listOf(ChapterWithSeries(chapter = availableChapter, series = fakeSeries)),
                            page = 0,
                            size = 20,
                            totalItems = 1L,
                        ),
                    )
            }
        testAppWithOpds(opdsWithChapters) {
            val body = client.get("/opds/v1/library-updates") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "Chapter 1")
        }
    }

    // genres feed

    @Test
    fun `genres feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/library/genres").status)
        }

    @Test
    fun `genres feed returns 200 with genre entries`() {
        val opdsWithGenres =
            object : OpdsReadApi by fakeOpdsRead {
                override suspend fun listGenres() = Result.Success(listOf(GenreCount("Action", 5)))
            }
        testAppWithOpds(opdsWithGenres) {
            val body = client.get("/opds/v1/library/genres") { basicAuth("user", "pass") }.bodyAsText()
            assertEquals(HttpStatusCode.OK, client.get("/opds/v1/library/genres") { basicAuth("user", "pass") }.status)
            assertContains(body, "Action")
        }
    }

    // languages feed

    @Test
    fun `languages feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/library/languages").status)
        }

    @Test
    fun `languages feed returns 200 with language entries`() {
        val opdsWithLanguages =
            object : OpdsReadApi by fakeOpdsRead {
                override suspend fun listLanguages() = Result.Success(listOf(LanguageCount("en", 8)))
            }
        testAppWithOpds(opdsWithLanguages) {
            val body = client.get("/opds/v1/library/languages") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "en")
        }
    }

    // status feed

    @Test
    fun `status feed returns 401 when unauthenticated`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/library/status").status)
        }

    @Test
    fun `status feed returns 200 with status entries`() {
        val opdsWithStatuses =
            object : OpdsReadApi by fakeOpdsRead {
                override suspend fun listUpstreamStatuses() = Result.Success(listOf(StatusCount("ONGOING", 3)))
            }
        testAppWithOpds(opdsWithStatuses) {
            val body = client.get("/opds/v1/library/status") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "ONGOING")
        }
    }

    // filtered catalog

    @Test
    fun `catalog filters reach the library query`() {
        val seen = mutableListOf<SeriesQuery>()
        testAppWith(capturingLibrary(seen)) {
            val res = client.get("/opds/v1/catalog?genre=Action&language=ja&upstreamStatus=ONGOING") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertContains(body, "Test Series")
            assertContains(body, "genre=Action")
        }
        assertEquals(listOf(SeriesQuery(genre = "Action", language = "ja", upstreamStatus = "ONGOING")), seen)
    }

    @Test
    fun `catalog without filter uses normal library path`() =
        testApp {
            val res = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "Test Series")
        }

    // sorting: OPDS 1.2 section 4, facets that reorder the publications of an acquisition feed

    private fun capturingLibrary(
        seen: MutableList<SeriesQuery>,
        total: Long = 1L,
    ) = object : LibraryReadApi by fakeLibraryRead {
        override suspend fun listSeries(
            query: SeriesQuery,
            request: PageRequest,
        ): Result<Pagination<Series>> {
            seen += query
            return Result.Success(Pagination(listOf(fakeSeries), request.page, request.size, total))
        }
    }

    private fun links(xml: String): List<Element> {
        val nodes = parseXml(xml).getElementsByTagNameNS(ATOM_NS, "link")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun sortFacets(xml: String): List<Element> =
        links(xml).filter { it.getAttribute("rel") == "http://opds-spec.org/facet" && it.getAttributeNS(OPDS_NS, "facetGroup") == "Sort" }

    @Test
    fun `the catalog offers title and recently added as sort facets with exactly one active`() {
        val seen = mutableListOf<SeriesQuery>()
        testAppWith(capturingLibrary(seen)) {
            val byTitle = sortFacets(client.get("/opds/v1/catalog") { basicAuth("user", "pass") }.bodyAsText())
            assertEquals(listOf("/opds/v1/catalog?sort=title", "/opds/v1/catalog?sort=added"), byTitle.map { it.getAttribute("href") })
            assertEquals(listOf("Title", "Recently added"), byTitle.map { it.getAttribute("title") })
            assertEquals(listOf("true", ""), byTitle.map { it.getAttributeNS(OPDS_NS, "activeFacet") })
            byTitle.forEach { assertEquals(FeedBuilder.TYPE_ACQUISITION, it.getAttribute("type")) }

            val byAdded = sortFacets(client.get("/opds/v1/catalog?sort=added") { basicAuth("user", "pass") }.bodyAsText())
            assertEquals(listOf("", "true"), byAdded.map { it.getAttributeNS(OPDS_NS, "activeFacet") })
        }
        assertEquals(listOf(SeriesQuery(), SeriesQuery(sort = SeriesSort.ADDED)), seen)
    }

    @Test
    fun `sort and filters are kept together in facet and paging links`() {
        val seen = mutableListOf<SeriesQuery>()
        testAppWith(capturingLibrary(seen, total = 45L)) {
            val body = client.get("/opds/v1/catalog?genre=Action&sort=added&page=0&size=20") { basicAuth("user", "pass") }.bodyAsText()
            assertEquals(
                listOf("/opds/v1/catalog?genre=Action&sort=title", "/opds/v1/catalog?genre=Action&sort=added"),
                sortFacets(body).map { it.getAttribute("href") },
            )
            assertContains(body, "href=\"/opds/v1/catalog?genre=Action&amp;sort=added&amp;page=1&amp;size=20\"")
        }
        assertEquals(listOf(SeriesQuery(sort = SeriesSort.ADDED, genre = "Action")), seen)
    }

    @Test
    fun `an unknown catalog sort is a bad request`() =
        testApp {
            assertEquals(HttpStatusCode.BadRequest, client.get("/opds/v1/catalog?sort=popular") { basicAuth("user", "pass") }.status)
        }

    @Test
    fun `the library updates entry uses the OPDS sort new relation`() =
        testApp {
            val body = client.get("/opds/v1") { basicAuth("user", "pass") }.bodyAsText()
            val toUpdates = links(body).filter { it.getAttribute("href") == "/opds/v1/library-updates" }
            assertEquals(listOf("http://opds-spec.org/sort/new"), toUpdates.map { it.getAttribute("rel") })
        }

    // search: OPDS 1.2 section 3 points to an OpenSearch 1.1 description document

    @Test
    fun `every feed has exactly one search link to the OpenSearch description`() =
        testApp {
            listOf(
                "/opds/v1",
                "/opds/v1/catalog",
                "/opds/v1/catalog?genre=Action",
                "/opds/v1/history",
                "/opds/v1/library-updates",
                "/opds/v1/library/genres",
                "/opds/v1/series/$seriesId",
                "/opds/v1/search?q=test",
            ).forEach { path ->
                val body = client.get(path) { basicAuth("user", "pass") }.bodyAsText()
                val links = Regex("<link [^>]*rel=\"search\"[^>]*>").findAll(body).map { it.value }.toList()
                assertEquals(1, links.size, "$path: $links")
                assertContains(links.single(), "href=\"/opds/v1/search.xml\"")
                assertContains(links.single(), "type=\"application/opensearchdescription+xml\"")
            }
        }

    @Test
    fun `the OpenSearch description has the required elements and an absolute acquisition template`() =
        testApp {
            val res = client.get("/opds/v1/search.xml") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("application/opensearchdescription+xml") == true, res.contentType().toString())
            val root = parseXml(res.bodyAsText()).documentElement
            assertEquals("OpenSearchDescription", root.localName)
            assertEquals(OPENSEARCH_NS, root.namespaceURI)
            assertEquals("ChapterVault", root.child("ShortName").textContent)
            assertTrue(root.child("Description").textContent.isNotBlank())
            val url = root.child("Url")
            assertEquals("application/atom+xml;profile=opds-catalog;kind=acquisition", url.getAttribute("type"))
            assertEquals("http://localhost/opds/v1/search?q={searchTerms}", url.getAttribute("template"))
        }

    @Test
    fun `the search template uses the configured public url`() =
        testApp(publicUrl = "https://manga.example.com/") {
            val root = parseXml(client.get("/opds/v1/search.xml") { basicAuth("user", "pass") }.bodyAsText()).documentElement
            assertEquals("https://manga.example.com/opds/v1/search?q={searchTerms}", root.child("Url").getAttribute("template"))
        }

    @Test
    fun `without a public url the search template follows the Host header`() =
        testApp {
            val body =
                client
                    .get("/opds/v1/search.xml") {
                        basicAuth("user", "pass")
                        header(HttpHeaders.Host, "nas.local:18080")
                    }.bodyAsText()
            assertEquals(
                "http://nas.local:18080/opds/v1/search?q={searchTerms}",
                parseXml(body).documentElement.child("Url").getAttribute("template"),
            )
        }

    @Test
    fun `search returns an acquisition feed of matching series with paging that keeps the query`() {
        var seen: Pair<String, PageRequest>? = null
        val searching =
            object : LibraryReadApi by fakeLibraryRead {
                override suspend fun searchLibrary(
                    query: String,
                    request: PageRequest,
                ): Result<Pagination<Series>> {
                    seen = query to request
                    return Result.Success(Pagination(List(request.size) { fakeSeries }, request.page, request.size, 25L))
                }
            }
        testAppWith(searching) {
            val res = client.get("/opds/v1/search?q=one%20piece&page=1&size=10") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.contentType()?.match("application/atom+xml") == true)
            assertEquals("one piece", seen?.first)
            assertEquals(1, seen?.second?.page)
            val body = res.bodyAsText()
            assertContains(body, "Test Series")
            assertContains(body, "kind=acquisition")
            assertContains(body, "href=\"/opds/v1/search?q=one+piece&amp;page=2&amp;size=10\"")
            assertContains(body, "href=\"/opds/v1/search?q=one+piece&amp;page=0&amp;size=10\"")
            assertContains(body, ">25<")
        }
    }

    @Test
    fun `search without search terms is a bad request`() =
        testApp {
            listOf("/opds/v1/search", "/opds/v1/search?q=", "/opds/v1/search?q=%20%20").forEach { path ->
                assertEquals(HttpStatusCode.BadRequest, client.get(path) { basicAuth("user", "pass") }.status, path)
            }
        }

    @Test
    fun `search and its description need credentials`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/search.xml").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/search?q=test").status)
        }

    private fun parseXml(xml: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    private fun Element.child(name: String): Element =
        assertNotNull(getElementsByTagNameNS(OPENSEARCH_NS, name).item(0) as Element?, "missing $name")

    private companion object {
        const val OPENSEARCH_NS = "http://a9.com/-/spec/opensearch/1.1/"
        const val ATOM_NS = "http://www.w3.org/2005/Atom"
        const val OPDS_NS = "http://opds-spec.org/2010/catalog"
    }
}
