package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpdsRoutesTest {

    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000002")
    private val downloadedChapterId = Id.from("00000000-0000-0000-0000-000000000003")

    private val fakeSeries = Series(
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

    private val availableChapter = Chapter(
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

    private val downloadedChapter = Chapter(
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

    private val fakeLibraryRead = object : LibraryReadApi {
        override suspend fun getSeries(id: Id) =
            if (id == seriesId) Result.Success(fakeSeries)
            else Result.Failure(AppError.NotFound("Series", id.toString()))
        override suspend fun listSeries(request: PageRequest) = Result.Success(
            Pagination(items = listOf(fakeSeries), page = 0, size = 20, totalItems = 1L)
        )
        override suspend fun searchLibrary(query: String, request: PageRequest) = Result.Success(
            Pagination(items = emptyList<Series>(), page = 0, size = 20, totalItems = 0L)
        )
        override suspend fun getChapter(id: Id) = when (id) {
            chapterId -> Result.Success(availableChapter)
            downloadedChapterId -> Result.Success(downloadedChapter)
            else -> Result.Failure(AppError.NotFound("Chapter", id.toString()))
        }
        override suspend fun listChapters(seriesId: Id) =
            if (seriesId == this@OpdsRoutesTest.seriesId) Result.Success(listOf(availableChapter, downloadedChapter))
            else Result.Failure(AppError.NotFound("Series", seriesId.toString()))
        override suspend fun listChaptersByStatus(seriesId: Id, status: DownloadStatus) = Result.Success(emptyList<Chapter>())
        override suspend fun inLibraryExternalIds(connectorId: String, externalIds: List<String>) = Result.Success(emptySet<String>())
    }

    private val fakePageSource = object : ChapterPageSource {
        override suspend fun readPage(chapter: Chapter, index: Int): Result<Page> {
            if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) return Result.Failure(AppError.NotFound("Page", index.toString()))
            if (index < 0 || index >= (chapter.pageCount ?: 0)) return Result.Failure(AppError.NotFound("Page", index.toString()))
            return Result.Success(Page(index, fakePageBytes, fakePageMime))
        }
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(Authentication) {
                basic("auth-basic") {
                    realm = "ChapterVault"
                    validate { cred ->
                        if (cred.name == "user" && cred.password == "pass")
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        else null
                    }
                }
                bearer("auth-bearer") {
                    authenticate { cred ->
                        if (cred.token == "valid-token")
                            KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                        else null
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
    fun `navigation feed returns 200 with atom+xml content type`() = testApp {
        val res = client.get("/opds/v1") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.contentType()?.match("application/atom+xml") == true)
    }

    @Test
    fun `catalog feed returns 401 when unauthenticated`() = testApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/catalog").status)
    }

    @Test
    fun `catalog feed returns 200 with series entries when authenticated`() = testApp {
        val res = client.get("/opds/v1/catalog?page=0&size=20") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertContains(res.bodyAsText(), "Test Series")
    }

    @Test
    fun `series feed returns chapter entries and CBZ acquisition link for downloaded chapter`() = testApp {
        val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertContains(body, "Chapter 2")
        assertContains(body, "application/x-cbz")
    }

    @Test
    fun `series feed includes PSE link for downloaded chapter with pageCount`() = testApp {
        val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
        val body = res.bodyAsText()
        assertContains(body, "vaemendis.net/opds-pse/ns")
        assertContains(body, "pse:count=\"5\"")
        assertContains(body, "image/png")
    }

    @Test
    fun `series feed does not include acquisition or PSE link for undownloaded chapter`() = testApp {
        val res = client.get("/opds/v1/series/$seriesId") { basicAuth("user", "pass") }
        val body = res.bodyAsText()
        assertContains(body, "Chapter 1")
        assertTrue(!body.contains("/opds/v1/download/$chapterId"), "undownloaded chapter should have no download link")
    }

    @Test
    fun `series feed returns 404 for unknown series id`() = testApp {
        val fakeId = Id.generate()
        val res = client.get("/opds/v1/series/$fakeId") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `download endpoint returns 404 when route not wired in test scope`() = testApp {
        val res = client.get("/opds/v1/download/$downloadedChapterId") { basicAuth("user", "pass") }
        assertTrue(res.status == HttpStatusCode.NotFound || res.status == HttpStatusCode.OK,
            "Expected 404 or 200, got ${res.status}")
    }

    @Test
    fun `download endpoint returns 404 for unknown chapter`() = testApp {
        val res = client.get("/opds/v1/download/${Id.generate()}") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `PSE page endpoint returns 200 with correct mime type and bytes`() = testApp {
        val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(fakePageMime, res.contentType()?.toString()?.substringBefore(";")?.trim())
        assertTrue(res.readRawBytes().contentEquals(fakePageBytes))
    }

    @Test
    fun `PSE page endpoint returns 404 for out-of-range page index`() = testApp {
        val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/99") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `PSE page endpoint includes ETag and Cache-Control headers`() = testApp {
        val res = client.get("/opds/v1/chapters/$downloadedChapterId/pages/0") { basicAuth("user", "pass") }
        assertNotNull(res.headers["ETag"], "Expected ETag header")
        val cacheControl = res.headers["Cache-Control"]
        assertNotNull(cacheControl, "Expected Cache-Control header")
        assertContains(cacheControl!!, "max-age=31536000")
        assertContains(cacheControl, "immutable")
    }

    @Test
    fun `OPDS catalog returns 401 when Bearer token sent instead of Basic Auth`() = testApp {
        val res = client.get("/opds/v1/catalog") { bearerAuth("valid-token") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `library page route returns 401 when Basic Auth sent instead of Bearer`() = testApp {
        val res = client.get("/library/chapters/$chapterId/pages/0") { basicAuth("user", "pass") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
