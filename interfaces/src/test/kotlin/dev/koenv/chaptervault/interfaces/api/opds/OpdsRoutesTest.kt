package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
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
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
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

            override suspend fun listSeries(request: PageRequest) =
                Result.Success(
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
            install(Authentication) {
                basic("auth-basic") {
                    realm = "ChapterVault"
                    validate { cred ->
                        if (cred.name == "user" && cred.password == "pass") {
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        } else {
                            null
                        }
                    }
                }
            }
            opdsRoutes(libraryRead, fakePageSource)
            opdsPageRoutes(libraryRead, fakePageSource)
        }
        block()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                install(Authentication) {
                    basic("auth-basic") {
                        realm = "ChapterVault"
                        validate { cred ->
                            if (cred.name == "user" && cred.password == "pass") {
                                KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
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
                opdsRoutes(fakeLibraryRead, fakePageSource)
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
            assertContains(body, "type=\"image/jpeg\"")
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

    // Feed structure

    @Test
    fun `navigation feed contains self link start link and catalog subsection link`() {
        testApp {
            val body = client.get("/opds/v1") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "rel=\"self\"")
            assertContains(body, "rel=\"start\"")
            assertContains(body, "rel=\"subsection\"")
            assertContains(body, "/opds/v1/catalog")
        }
    }

    @Test
    fun `catalog feed includes OpenSearch total results and items per page`() {
        testApp {
            val body = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }.bodyAsText()
            assertContains(body, "<os:totalResults>1</os:totalResults>")
            assertContains(body, "<os:itemsPerPage>20</os:itemsPerPage>")
            assertContains(body, "<os:startIndex>1</os:startIndex>")
        }
    }

    @Test
    fun `catalog feed returns 200 with no entries when library is empty`() {
        val emptyLibrary =
            object : LibraryReadApi {
                override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))

                override suspend fun listSeries(request: PageRequest) =
                    Result.Success(Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L))

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
            }
        testAppWith(emptyLibrary) {
            val res = client.get("/opds/v1/catalog") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertContains(body, "<os:totalResults>0</os:totalResults>")
            assertTrue(!body.contains("<entry>"), "expected no entries in empty catalog")
        }
    }

    @Test
    fun `catalog feed includes next link when total items exceed page size`() {
        val paginatedLibrary =
            object : LibraryReadApi {
                override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)

                override suspend fun listSeries(request: PageRequest) =
                    Result.Success(
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
            }
        testAppWith(paginatedLibrary) {
            val res = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "rel=\"next\"")
        }
    }

    // Input validation

    @Test
    fun `series feed returns 400 for malformed series ID`() {
        testApp {
            val res = client.get("/opds/v1/series/not-a-uuid") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
    }

    @Test
    fun `PSE page endpoint returns 400 for malformed chapter ID`() {
        testApp {
            val res = client.get("/opds/v1/chapters/not-a-uuid/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
    }

    // Page access edge cases

    @Test
    fun `PSE page endpoint returns 404 for non-downloaded chapter`() {
        testApp {
            val res = client.get("/opds/v1/chapters/$chapterId/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }
    }

    @Test
    fun `PSE page endpoint returns 404 for unknown chapter ID`() {
        testApp {
            val res = client.get("/opds/v1/chapters/${Id.generate()}/pages/0") { basicAuth("user", "pass") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }
    }

    // Caching

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

    // Content-type negotiation

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
}
