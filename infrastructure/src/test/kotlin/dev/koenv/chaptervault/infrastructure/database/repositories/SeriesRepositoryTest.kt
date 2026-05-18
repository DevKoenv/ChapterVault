package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeriesRepositoryTest {
    private val repo = SeriesRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("chaptervault-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(SeriesTable, ChapterTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(ChapterTable, SeriesTable)
            SchemaUtils.create(SeriesTable, ChapterTable)
        }
    }

    @Test
    fun `getSeries returns NotFound when series does not exist`() {
        runBlocking {
            val result = repo.getSeries(Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `addToLibrary creates and returns series`() = runBlocking {
        val result = repo.addToLibrary("mangadex", "ext-123", autoDownload = false)
        assertIs<Result.Success<*>>(result)
        val series = (result as Result.Success).value
        assertEquals("mangadex", series.connectorId)
        assertEquals("ext-123", series.externalId)
        assertEquals(false, series.autoDownload)
    }

    @Test
    fun `getSeries returns series after it is added`() = runBlocking {
        val added = (repo.addToLibrary("mangadex", "ext-456", autoDownload = true) as Result.Success).value

        val result = repo.getSeries(added.id)
        assertIs<Result.Success<*>>(result)
        val fetched = (result as Result.Success).value
        assertEquals(added.id, fetched.id)
        assertEquals("mangadex", fetched.connectorId)
        assertEquals(true, fetched.autoDownload)
    }

    @Test
    fun `listSeries returns empty pagination when no series exist`() = runBlocking {
        val result = repo.listSeries(PageRequest())
        assertIs<Result.Success<*>>(result)
        val page = (result as Result.Success).value
        assertEquals(0L, page.totalItems)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `listSeries returns pagination with items`() = runBlocking {
        repo.addToLibrary("mangadex", "ext-1", autoDownload = false)
        repo.addToLibrary("mangadex", "ext-2", autoDownload = false)

        val result = repo.listSeries(PageRequest())
        assertIs<Result.Success<*>>(result)
        val page = (result as Result.Success).value
        assertEquals(2L, page.totalItems)
        assertEquals(2, page.items.size)
    }

    @Test
    fun `addToLibrary returns Conflict when series already exists`() {
        runBlocking {
            repo.addToLibrary("mangadex", "ext-dup", autoDownload = false)
            val result = repo.addToLibrary("mangadex", "ext-dup", autoDownload = false)
            assertIs<Result.Failure>(result)
            assertIs<AppError.Conflict>((result as Result.Failure).error)
        }
    }

    @Test
    fun `addToLibrary allows same externalId with different language`() {
        runBlocking {
            val en = repo.addToLibrary("mangadex", "ext-lang", language = "en", autoDownload = false)
            val fr = repo.addToLibrary("mangadex", "ext-lang", language = "fr", autoDownload = false)
            assertIs<Result.Success<*>>(en)
            assertIs<Result.Success<*>>(fr)
            val dup = repo.addToLibrary("mangadex", "ext-lang", language = "en", autoDownload = false)
            assertIs<Result.Failure>(dup)
            assertIs<AppError.Conflict>((dup as Result.Failure).error)
        }
    }

    @Test
    fun `removeSeries removes the series`() {
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-rm", autoDownload = false) as Result.Success).value

            val removeResult = repo.removeSeries(series.id)
            assertIs<Result.Success<*>>(removeResult)

            val getResult = repo.getSeries(series.id)
            assertIs<Result.Failure>(getResult)
            assertIs<AppError.NotFound>((getResult as Result.Failure).error)
        }
    }

    @Test
    fun `removeSeries returns NotFound for unknown id`() {
        runBlocking {
            val result = repo.removeSeries(Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `updateSeries updates autoDownload`() = runBlocking {
        val series = (repo.addToLibrary("mangadex", "ext-upd", autoDownload = false) as Result.Success).value

        val result = repo.updateSeries(series.id, autoDownload = true)
        assertIs<Result.Success<*>>(result)
        assertEquals(true, (result as Result.Success).value.autoDownload)
    }

    @Test
    fun `updateSeries returns NotFound for unknown id`() {
        runBlocking {
            val result = repo.updateSeries(Id.generate(), autoDownload = true)
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `searchLibrary returns matching series by title`() = runBlocking {
        repo.addToLibrary("mangadex", "one-piece", autoDownload = false)
        repo.addToLibrary("mangadex", "naruto", autoDownload = false)

        val result = repo.searchLibrary("piece", PageRequest())
        assertIs<Result.Success<*>>(result)
        val page = (result as Result.Success).value
        assertEquals(1L, page.totalItems)
        assertEquals("one-piece", page.items.single().externalId)
    }

    @Test
    fun `listChapters returns empty list when no chapters for series`() = runBlocking {
        val series = (repo.addToLibrary("mangadex", "ext-ch", autoDownload = false) as Result.Success).value

        val result = repo.listChapters(series.id)
        assertIs<Result.Success<*>>(result)
        assertTrue((result as Result.Success).value.isEmpty())
    }

    @Test
    fun `listChapters returns empty list for unknown series id`() = runBlocking {
        val result = repo.listChapters(Id.generate())
        assertIs<Result.Success<*>>(result)
        assertTrue((result as Result.Success).value.isEmpty())
    }

    @Test
    fun `getChapter returns NotFound when chapter does not exist`() {
        runBlocking {
            val result = repo.getChapter(Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `updateMetadata updates title, coverUrl, and description`() {
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-meta", autoDownload = false) as Result.Success).value

            val result = repo.updateMetadata(series.id, "New Title", "https://cover.url/img.jpg", "A description")
            assertIs<Result.Success<*>>(result)

            val fetched = (repo.getSeries(series.id) as Result.Success).value
            assertEquals("New Title", fetched.title)
            assertEquals("https://cover.url/img.jpg", fetched.coverUrl)
            assertEquals("A description", fetched.description)
        }
    }

    @Test
    fun `updateMetadata returns NotFound for unknown id`() {
        runBlocking {
            val result = repo.updateMetadata(Id.generate(), "Title", null, null)
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }
}
