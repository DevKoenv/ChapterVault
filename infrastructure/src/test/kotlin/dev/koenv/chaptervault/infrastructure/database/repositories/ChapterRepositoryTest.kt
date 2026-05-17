package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChapterRepositoryTest {
    private val repo = ChapterRepository()

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

    private fun insertSeries(id: Id = Id.generate()): Id {
        transaction {
            SeriesTable.insert {
                it[SeriesTable.id] = id.toString()
                it[SeriesTable.title] = "Test Series"
                it[SeriesTable.connectorId] = "test"
                it[SeriesTable.externalId] = "ext-001"
                it[SeriesTable.status] = SeriesStatus.IN_LIBRARY.name
                it[SeriesTable.autoDownload] = false
                it[SeriesTable.defaultFormat] = null
                it[SeriesTable.coverUrl] = null
                it[SeriesTable.description] = null
                it[SeriesTable.addedAt] = Instant.now().toKotlinInstant()
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }
        return id
    }

    @Test
    fun `insertChapter creates and returns chapter with PENDING status`() {
        runBlocking {
            val seriesId = insertSeries()
            val result = repo.insertChapter(
                seriesId = seriesId,
                title = "Chapter 1",
                chapterIndex = 1.0,
                externalId = "ext-ch-001",
            )
            assertIs<Result.Success<*>>(result)
            val chapter = (result as Result.Success).value
            assertEquals(seriesId, chapter.seriesId)
            assertEquals("Chapter 1", chapter.title)
            assertEquals(1.0, chapter.chapterIndex)
            assertEquals("ext-ch-001", chapter.externalId)
            assertEquals(DownloadStatus.PENDING, chapter.downloadStatus)
        }
    }

    @Test
    fun `insertChapter with non-existent seriesId returns NotFound failure`() {
        runBlocking {
            val nonExistentSeriesId = Id.generate()
            val result = repo.insertChapter(
                seriesId = nonExistentSeriesId,
                title = "Chapter 1",
                chapterIndex = 1.0,
                externalId = "ext-ch-001",
            )
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `updateDownloadStatus changes status and returns updated chapter`() {
        runBlocking {
            val seriesId = insertSeries()
            val inserted = (repo.insertChapter(
                seriesId = seriesId,
                title = "Chapter 1",
                chapterIndex = 1.0,
                externalId = "ext-ch-001",
            ) as Result.Success).value

            val result = repo.updateDownloadStatus(inserted.id, DownloadStatus.DOWNLOADED)
            assertIs<Result.Success<*>>(result)
            val updated = (result as Result.Success).value
            assertEquals(inserted.id, updated.id)
            assertEquals(DownloadStatus.DOWNLOADED, updated.downloadStatus)
        }
    }

    @Test
    fun `updateDownloadStatus with non-existent id returns NotFound failure`() {
        runBlocking {
            val result = repo.updateDownloadStatus(Id.generate(), DownloadStatus.DOWNLOADED)
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `listChapters returns empty list when no chapters`() {
        runBlocking {
            val seriesId = insertSeries()
            val result = repo.listChapters(seriesId)
            assertIs<Result.Success<*>>(result)
            assertTrue((result as Result.Success).value.isEmpty())
        }
    }

    @Test
    fun `listChapters returns chapters ordered by chapterIndex`() {
        runBlocking {
            val seriesId = insertSeries()
            repo.insertChapter(seriesId = seriesId, title = "Chapter 3", chapterIndex = 3.0, externalId = "ext-ch-003")
            repo.insertChapter(seriesId = seriesId, title = "Chapter 1", chapterIndex = 1.0, externalId = "ext-ch-001")
            repo.insertChapter(seriesId = seriesId, title = "Chapter 2", chapterIndex = 2.0, externalId = "ext-ch-002")

            val result = repo.listChapters(seriesId)
            assertIs<Result.Success<*>>(result)
            val chapters = (result as Result.Success).value
            assertEquals(3, chapters.size)
            assertEquals(1.0, chapters[0].chapterIndex)
            assertEquals(2.0, chapters[1].chapterIndex)
            assertEquals(3.0, chapters[2].chapterIndex)
        }
    }
}
