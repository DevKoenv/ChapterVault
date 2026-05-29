package dev.koenv.chaptervault.extensions.connectors.sources.mangadex

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MangaDexConnectorTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun makeConnector(handler: (url: String) -> String): MangaDexConnector {
        val engine =
            MockEngine { request ->
                respond(handler(request.url.toString()), HttpStatusCode.OK, jsonHeaders)
            }
        return MangaDexConnector(HttpClient(engine))
    }

    private fun makeChapter(externalId: String) =
        Chapter(
            id = Id.generate(),
            seriesId = Id.generate(),
            title = "Chapter 1",
            chapterIndex = 1.0,
            externalId = externalId,
            downloadStatus = DownloadStatus.PENDING,
            addedAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertSuccess(result: Result<T>): T {
        assertTrue(result is Result.Success<*>, "Expected success but got: $result")
        return (result as Result.Success<T>).value
    }

    // ── search ────────────────────────────────────────────────────────────────────

    @Test
    fun `search maps title, description, and cover URL from response`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": [{
                    "id": "manga-uuid-1",
                    "attributes": {
                      "title": {"en": "One Piece"},
                      "description": {"en": "A pirate adventure"},
                      "status": "ongoing",
                      "contentRating": "safe"
                    },
                    "relationships": [{
                      "id": "cover-uuid-1",
                      "type": "cover_art",
                      "attributes": {"fileName": "cover.jpg"}
                    }]
                  }],
                  "limit": 20, "offset": 0, "total": 1
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.search("one piece", PageRequest(page = 0, size = 20)) }

        val page = assertSuccess(result)
        val item = page.items.single()
        assertEquals("manga-uuid-1", item.externalId)
        assertEquals("One Piece", item.title)
        assertEquals("A pirate adventure", item.description)
        assertEquals("https://uploads.mangadex.org/covers/manga-uuid-1/cover.jpg.512.jpg", item.coverUrl)
    }

    @Test
    fun `search calculates offset from page number`() {
        var capturedUrl = ""
        val connector =
            makeConnector { url ->
                capturedUrl = url
                """{"result":"ok","data":[],"limit":20,"offset":40,"total":100}"""
            }

        runBlocking { connector.search("", PageRequest(page = 2, size = 20)) }

        assertTrue(capturedUrl.contains("offset=40"), "Expected offset=40 in URL, got: $capturedUrl")
    }

    @Test
    fun `search omits title param when query is blank`() {
        var capturedUrl = ""
        val connector =
            makeConnector { url ->
                capturedUrl = url
                """{"result":"ok","data":[],"limit":20,"offset":0,"total":0}"""
            }

        runBlocking { connector.search("", PageRequest(page = 0, size = 20)) }

        assertTrue(!capturedUrl.contains("title="), "URL must not contain title= for blank query, got: $capturedUrl")
    }

    @Test
    fun `search returns pagination with correct totals`() {
        val connector =
            makeConnector {
                """{"result":"ok","data":[],"limit":20,"offset":0,"total":250}"""
            }

        val result = runBlocking { connector.search("", PageRequest(page = 0, size = 20)) }

        val page = assertSuccess(result)
        assertEquals(250L, page.totalItems)
        assertEquals(0, page.page)
        assertEquals(20, page.size)
    }

    // ── fetchSeries ───────────────────────────────────────────────────────────────

    @Test
    fun `fetchSeries maps title, description, and cover URL`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": {
                    "id": "manga-uuid-1",
                    "attributes": {
                      "title": {"en": "Naruto"},
                      "description": {"en": "A ninja story"},
                      "status": "completed"
                    },
                    "relationships": [{
                      "id": "cover-uuid-1",
                      "type": "cover_art",
                      "attributes": {"fileName": "naruto-cover.jpg"}
                    }]
                  }
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.fetchSeries("manga-uuid-1") }

        val meta = assertSuccess(result)
        assertEquals("manga-uuid-1", meta.externalId)
        assertEquals("Naruto", meta.title)
        assertEquals("A ninja story", meta.description)
        assertEquals("https://uploads.mangadex.org/covers/manga-uuid-1/naruto-cover.jpg.512.jpg", meta.coverUrl)
    }

    @Test
    fun `fetchSeries returns failure when data is null`() {
        val connector = makeConnector { """{"result":"ok","data":null}""" }

        val result = runBlocking { connector.fetchSeries("missing-uuid") }

        assertIs<Result.Failure>(result)
    }

    @Test
    fun `fetchSeries falls back to first available title when english title is absent`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": {
                    "id": "manga-uuid-jp",
                    "attributes": {"title": {"ja": "ワンピース"}, "description": {}},
                    "relationships": []
                  }
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.fetchSeries("manga-uuid-jp") }

        val meta = assertSuccess(result)
        assertEquals("ワンピース", meta.title)
    }

    // ── fetchChapters ─────────────────────────────────────────────────────────────

    @Test
    fun `fetchChapters maps chapter number, title, and page count`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": [{
                    "id": "ch-uuid-1",
                    "attributes": {
                      "title": "Romance Dawn",
                      "volume": "1", "chapter": "1",
                      "pages": 53, "externalUrl": null
                    }
                  }],
                  "limit": 500, "offset": 0, "total": 1
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.fetchChapters("manga-uuid-1") }

        val chapters = assertSuccess(result)
        val ch = chapters.single()
        assertEquals("ch-uuid-1", ch.externalId)
        assertEquals("Romance Dawn", ch.title)
        assertEquals(1.0, ch.chapterIndex)
        assertEquals(53, ch.pageCount)
    }

    @Test
    fun `fetchChapters filters out chapters with external URL`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": [
                    {"id": "ch-ext", "attributes": {"title": "External", "chapter": "1", "pages": 0, "externalUrl": "https://site.com/ch1"}},
                    {"id": "ch-ok",  "attributes": {"title": "Normal",   "chapter": "2", "pages": 20, "externalUrl": null}}
                  ],
                  "limit": 500, "offset": 0, "total": 2
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.fetchChapters("manga-uuid-1") }

        val chapters = assertSuccess(result)
        assertEquals(1, chapters.size)
        assertEquals("ch-ok", chapters.single().externalId)
    }

    @Test
    fun `fetchChapters filters out zero-page chapters`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "data": [
                    {"id": "ch-empty", "attributes": {"title": "No Pages",  "chapter": "1", "pages": 0,  "externalUrl": null}},
                    {"id": "ch-full",  "attributes": {"title": "Has Pages", "chapter": "2", "pages": 15, "externalUrl": null}}
                  ],
                  "limit": 500, "offset": 0, "total": 2
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.fetchChapters("manga-uuid-1") }

        val chapters = assertSuccess(result)
        assertEquals(1, chapters.size)
        assertEquals("ch-full", chapters.single().externalId)
    }

    @Test
    fun `fetchChapters paginates until all chapters are fetched`() {
        var callCount = 0
        val engine =
            MockEngine { request ->
                callCount++
                val offset =
                    Regex("offset=(\\d+)")
                        .find(request.url.toString())
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: 0
                val page0 =
                    """{"result":"ok","data":[{"id":"ch-1","attributes":""" +
                        """{"title":"Ch 1","chapter":"1","pages":10,"externalUrl":null}}],""" +
                        """"limit":500,"offset":0,"total":2}"""
                val page1 =
                    """{"result":"ok","data":[{"id":"ch-2","attributes":""" +
                        """{"title":"Ch 2","chapter":"2","pages":10,"externalUrl":null}}],""" +
                        """"limit":500,"offset":1,"total":2}"""
                val body =
                    when (offset) {
                        0 -> page0
                        1 -> page1
                        else -> """{"result":"ok","data":[],"limit":500,"offset":$offset,"total":2}"""
                    }
                respond(body, HttpStatusCode.OK, jsonHeaders)
            }
        val connector = MangaDexConnector(HttpClient(engine))

        val result = runBlocking { connector.fetchChapters("manga-uuid-1") }

        val chapters = assertSuccess(result)
        assertEquals(2, chapters.size)
        assertEquals(2, callCount)
    }

    // ── download ──────────────────────────────────────────────────────────────────

    @Test
    fun `download builds page URLs from at-home server response`() {
        val connector =
            makeConnector {
                """
                {
                  "result": "ok",
                  "baseUrl": "https://cdn-node.mangadex.org",
                  "chapter": {
                    "hash": "abc123hash",
                    "data": ["page1.jpg", "page2.jpg", "page3.jpg"]
                  }
                }
                """.trimIndent()
            }

        val result = runBlocking { connector.download(makeChapter("chapter-uuid-1"), ChapterFormat.Cbz) }

        val download = assertSuccess(result)
        assertEquals(3, download.pages.size)
        assertEquals("https://cdn-node.mangadex.org/data/abc123hash/page1.jpg", download.pages[0].url)
        assertEquals("https://cdn-node.mangadex.org/data/abc123hash/page3.jpg", download.pages[2].url)
        assertEquals(0, download.pages[0].index)
        assertEquals(2, download.pages[2].index)
    }

    @Test
    fun `download caches at-home token and avoids redundant API calls`() {
        var atHomeCallCount = 0
        val engine =
            MockEngine { request ->
                if (request.url.toString().contains("/at-home/server/")) atHomeCallCount++
                respond(
                    """{"result":"ok","baseUrl":"https://cdn.mangadex.org","chapter":{"hash":"xyz","data":["p1.jpg"]}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        val connector = MangaDexConnector(HttpClient(engine))
        val chapter = makeChapter("chapter-uuid-cached")

        runBlocking {
            connector.download(chapter, ChapterFormat.Cbz)
            connector.download(chapter, ChapterFormat.Cbz)
        }

        assertEquals(1, atHomeCallCount)
    }

    @Test
    fun `download returns failure when chapter has no pages`() {
        val connector =
            makeConnector {
                """{"result":"ok","baseUrl":"https://cdn.mangadex.org","chapter":{"hash":"abc123","data":[]}}"""
            }

        val result = runBlocking { connector.download(makeChapter("chapter-uuid-empty"), ChapterFormat.Cbz) }

        assertIs<Result.Failure>(result)
    }
}
