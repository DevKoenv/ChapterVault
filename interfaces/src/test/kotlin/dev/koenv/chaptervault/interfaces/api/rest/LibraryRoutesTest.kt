package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertContains

class LibraryRoutesTest {
    private val fakeSeries = Series(
        id = Id.from("00000000-0000-0000-0000-000000000001"),
        title = "One Piece",
        connectorId = "mangadex",
        externalId = "ext-001",
        status = SeriesStatus.IN_LIBRARY,
        autoDownload = false,
        addedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private val fakeChapter = Chapter(
        id = Id.from("00000000-0000-0000-0000-000000000002"),
        seriesId = fakeSeries.id,
        title = "Chapter 1",
        chapterIndex = 1.0,
        externalId = "ch-001",
        downloadStatus = DownloadStatus.PENDING,
        addedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun testApp(
        readApi: LibraryReadApi,
        commandApi: LibraryCommandApi,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            libraryRoutes(readApi, commandApi)
        }
        block()
    }

    @Test
    fun `GET library series returns 200 with paginated list`() = testApp(
        readApi = object : LibraryReadApi {
            override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))
            override suspend fun listSeries(request: PageRequest) =
                Result.Success(Pagination(listOf(fakeSeries), 0, 20, 1L))
            override suspend fun searchLibrary(query: String, request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
            override suspend fun listChapters(seriesId: Id) = Result.Success(emptyList<Chapter>())
        },
        commandApi = NoOpCommandApi(),
    ) {
        val response = client.get("/library/series")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "One Piece")
        assertContains(body, "totalItems")
    }

    @Test
    fun `GET library series by id returns 200`() = testApp(
        readApi = object : LibraryReadApi {
            override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)
            override suspend fun listSeries(request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun searchLibrary(query: String, request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
            override suspend fun listChapters(seriesId: Id) = Result.Success(emptyList<Chapter>())
        },
        commandApi = NoOpCommandApi(),
    ) {
        val response = client.get("/library/series/00000000-0000-0000-0000-000000000001")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "One Piece")
    }

    @Test
    fun `GET library series by id returns 404 when not found`() = testApp(
        readApi = object : LibraryReadApi {
            override suspend fun getSeries(id: Id) =
                Result.Failure(AppError.NotFound("Series", id.toString()))
            override suspend fun listSeries(request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun searchLibrary(query: String, request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
            override suspend fun listChapters(seriesId: Id) = Result.Success(emptyList<Chapter>())
        },
        commandApi = NoOpCommandApi(),
    ) {
        val response = client.get("/library/series/00000000-0000-0000-0000-000000000099")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST library series returns 201 with created series`() = testApp(
        readApi = NoOpReadApi(),
        commandApi = object : LibraryCommandApi {
            override suspend fun addToLibrary(connectorId: String, externalId: String, autoDownload: Boolean) =
                Result.Success(fakeSeries)
            override suspend fun removeSeries(id: Id) = Result.Success(Unit)
            override suspend fun updateSeries(id: Id, autoDownload: Boolean?, defaultFormat: ChapterFormat?) =
                Result.Success(fakeSeries)
        },
    ) {
        val response = client.post("/library/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertContains(response.bodyAsText(), "One Piece")
    }

    @Test
    fun `POST library series returns 409 on conflict`() = testApp(
        readApi = NoOpReadApi(),
        commandApi = object : LibraryCommandApi {
            override suspend fun addToLibrary(connectorId: String, externalId: String, autoDownload: Boolean) =
                Result.Failure(AppError.Conflict("Already in library"))
            override suspend fun removeSeries(id: Id) = Result.Success(Unit)
            override suspend fun updateSeries(id: Id, autoDownload: Boolean?, defaultFormat: ChapterFormat?) =
                Result.Success(fakeSeries)
        },
    ) {
        val response = client.post("/library/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectorId":"mangadex","externalId":"ext-001"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `GET library series chapters returns 200 with chapter list`() = testApp(
        readApi = object : LibraryReadApi {
            override suspend fun getSeries(id: Id) = Result.Success(fakeSeries)
            override suspend fun listSeries(request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun searchLibrary(query: String, request: PageRequest) =
                Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
            override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
            override suspend fun listChapters(seriesId: Id) = Result.Success(listOf(fakeChapter))
        },
        commandApi = NoOpCommandApi(),
    ) {
        val response = client.get("/library/series/00000000-0000-0000-0000-000000000001/chapters")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Chapter 1")
    }
}

private class NoOpReadApi : LibraryReadApi {
    override suspend fun getSeries(id: Id) = Result.Failure(AppError.NotFound("Series", id.toString()))
    override suspend fun listSeries(request: PageRequest) =
        Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
    override suspend fun searchLibrary(query: String, request: PageRequest) =
        Result.Success(Pagination(emptyList<Series>(), 0, 20, 0L))
    override suspend fun getChapter(id: Id) = Result.Failure(AppError.NotFound("Chapter", id.toString()))
    override suspend fun listChapters(seriesId: Id) = Result.Success(emptyList<Chapter>())
}

private class NoOpCommandApi : LibraryCommandApi {
    override suspend fun addToLibrary(connectorId: String, externalId: String, autoDownload: Boolean) =
        Result.Failure(AppError.InternalError("not implemented"))
    override suspend fun removeSeries(id: Id) = Result.Success(Unit)
    override suspend fun updateSeries(id: Id, autoDownload: Boolean?, defaultFormat: ChapterFormat?) =
        Result.Failure(AppError.InternalError("not implemented"))
}
