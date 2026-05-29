package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesRepositoryTest {
    private val mockStorage = CapturingFileStorage()
    private val repo = SeriesRepository(mockStorage)

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("chaptervault-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect(
                "jdbc:sqlite:${dbFile.absolutePath}",
                driver = "org.sqlite.JDBC",
                setupConnection = { it.createStatement().execute("PRAGMA foreign_keys = ON") },
            )
            transaction {
                SchemaUtils.create(UserTable, SeriesTable, ChapterTable, TaskTable, ProgressTable, BookmarkTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        mockStorage.reset()
        transaction {
            SchemaUtils.drop(BookmarkTable, ProgressTable, TaskTable, ChapterTable, SeriesTable, UserTable)
            SchemaUtils.create(UserTable, SeriesTable, ChapterTable, TaskTable, ProgressTable, BookmarkTable)
        }
    }

    // --- cascade delete tests ---

    @Test
    fun `removeSeries deletes chapters, progress, bookmarks but preserves tasks`() =
        runBlocking {
            val userId = insertUser()
            val series = (repo.addToLibrary("mangadex", "ext-cascade", autoDownload = false) as Result.Success).value
            val chapterId = insertChapter(Id.from(series.id.toString()))
            insertProgress(userId, chapterId)
            insertBookmark(userId, chapterId)
            insertTask(chapterId.toString())
            insertTask(series.id.toString())

            val result = repo.removeSeries(series.id)
            assertIs<Result.Success<Unit>>(result)

            transaction {
                assertEquals(0L, ChapterTable.selectAll().where { ChapterTable.seriesId eq series.id.toString() }.count())
                assertEquals(0L, ProgressTable.selectAll().count())
                assertEquals(0L, BookmarkTable.selectAll().count())
                assertEquals(2L, TaskTable.selectAll().count())
            }
        }

    @Test
    fun `removeSeries calls deleteSeriesFiles after the DB commit`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-del-files", autoDownload = false) as Result.Success).value

            repo.removeSeries(series.id)

            assertEquals(listOf(series.id.toString()), mockStorage.deletedSeries)
        }

    @Test
    fun `removeSeries succeeds even when deleteSeriesFiles throws`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-del-fail", autoDownload = false) as Result.Success).value
            mockStorage.shouldFail = true

            val result = repo.removeSeries(series.id)

            assertIs<Result.Success<Unit>>(result)
        }

    // --- existing tests ---

    @Test
    fun `getSeries returns NotFound when series does not exist`() {
        runBlocking {
            val result = repo.getSeries(Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `addToLibrary creates and returns series`() =
        runBlocking {
            val result = repo.addToLibrary("mangadex", "ext-123", autoDownload = false)
            assertIs<Result.Success<*>>(result)
            val series = (result as Result.Success).value
            assertEquals("mangadex", series.connectorId)
            assertEquals("ext-123", series.externalId)
            assertEquals(false, series.autoDownload)
        }

    @Test
    fun `getSeries returns series after it is added`() =
        runBlocking {
            val added = (repo.addToLibrary("mangadex", "ext-456", autoDownload = true) as Result.Success).value

            val result = repo.getSeries(added.id)
            assertIs<Result.Success<*>>(result)
            val fetched = (result as Result.Success).value
            assertEquals(added.id, fetched.id)
            assertEquals("mangadex", fetched.connectorId)
            assertEquals(true, fetched.autoDownload)
        }

    @Test
    fun `listSeries returns empty pagination when no series exist`() =
        runBlocking {
            val result = repo.listSeries(PageRequest())
            assertIs<Result.Success<*>>(result)
            val page = (result as Result.Success).value
            assertEquals(0L, page.totalItems)
            assertTrue(page.items.isEmpty())
        }

    @Test
    fun `listSeries returns pagination with items`() =
        runBlocking {
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
    fun `updateSeries updates autoDownload`() =
        runBlocking {
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
    fun `searchLibrary returns matching series by title`() =
        runBlocking {
            repo.addToLibrary("mangadex", "one-piece", autoDownload = false)
            repo.addToLibrary("mangadex", "naruto", autoDownload = false)

            val result = repo.searchLibrary("piece", PageRequest())
            assertIs<Result.Success<*>>(result)
            val page = (result as Result.Success).value
            assertEquals(1L, page.totalItems)
            assertEquals("one-piece", page.items.single().externalId)
        }

    @Test
    fun `listChapters returns empty list when no chapters for series`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-ch", autoDownload = false) as Result.Success).value

            val result = repo.listChapters(series.id)
            assertIs<Result.Success<*>>(result)
            assertTrue((result as Result.Success).value.isEmpty())
        }

    @Test
    fun `listChapters returns empty list for unknown series id`() =
        runBlocking {
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
    fun `evictChapter resets status to AVAILABLE and keeps the record`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-evict", autoDownload = false) as Result.Success).value
            val chapterId = insertChapter(Id.from(series.id.toString()))
            transaction {
                ChapterTable.update({ ChapterTable.id eq chapterId.toString() }) {
                    it[ChapterTable.downloadStatus] = DownloadStatus.DOWNLOADED.name
                    it[ChapterTable.format] = "CBZ"
                    it[ChapterTable.pageCount] = 10
                }
            }

            val result = repo.evictChapter(Id.from(chapterId.toString()))
            assertIs<Result.Success<Unit>>(result)

            val chapter = (repo.getChapter(Id.from(chapterId.toString())) as Result.Success).value
            assertEquals(DownloadStatus.AVAILABLE, chapter.downloadStatus)
            assertNull(chapter.format)
            assertNull(chapter.pageCount)
        }

    @Test
    fun `evictChapter returns NotFound for unknown chapter`() =
        runBlocking {
            val result = repo.evictChapter(Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
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

    // --- helpers ---

    private fun insertUser(id: Id = Id.generate()): Id {
        transaction {
            UserTable.insert {
                it[UserTable.id] = id.toString()
                it[UserTable.username] = "user-$id"
                it[UserTable.passwordHash] = "hash"
                it[UserTable.roles] = "USER"
                it[UserTable.createdAt] = Instant.now().toKotlinInstant()
            }
        }
        return id
    }

    private fun insertChapter(
        seriesId: Id,
        id: Id = Id.generate(),
    ): Id {
        transaction {
            ChapterTable.insert {
                it[ChapterTable.id] = id.toString()
                it[ChapterTable.seriesId] = seriesId.toString()
                it[ChapterTable.title] = "Chapter"
                it[ChapterTable.chapterIndex] = 1.0
                it[ChapterTable.externalId] = "ext-ch-$id"
                it[ChapterTable.downloadStatus] = DownloadStatus.PENDING.name
                it[ChapterTable.addedAt] = Instant.now().toKotlinInstant()
                it[ChapterTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }
        return id
    }

    private fun insertProgress(
        userId: Id,
        chapterId: Id,
    ) {
        transaction {
            ProgressTable.insert {
                it[ProgressTable.userId] = userId.toString()
                it[ProgressTable.chapterId] = chapterId.toString()
                it[ProgressTable.readAt] = Instant.now().toKotlinInstant()
            }
        }
    }

    private fun insertBookmark(
        userId: Id,
        chapterId: Id,
    ) {
        transaction {
            BookmarkTable.insert {
                it[BookmarkTable.id] = Id.generate().toString()
                it[BookmarkTable.userId] = userId.toString()
                it[BookmarkTable.chapterId] = chapterId.toString()
                it[BookmarkTable.page] = 1
                it[BookmarkTable.createdAt] = Instant.now().toKotlinInstant()
            }
        }
    }

    private fun insertTask(targetId: String) {
        transaction {
            TaskTable.insert {
                it[TaskTable.id] = Id.generate().toString()
                it[TaskTable.type] = "DOWNLOAD_CHAPTER"
                it[TaskTable.status] = "PENDING"
                it[TaskTable.targetType] = "CHAPTER"
                it[TaskTable.targetId] = targetId
                it[TaskTable.createdAt] = Instant.now().toKotlinInstant()
                it[TaskTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }
    }
}

private class CapturingFileStorage(
    basePath: Path = Paths.get(System.getProperty("java.io.tmpdir")),
) : FileStorage(basePath, basePath, ArchiveWriterSelector(emptyList())) {
    val deletedSeries = mutableListOf<String>()
    var shouldFail = false

    override fun deleteSeriesFiles(seriesId: String) {
        if (shouldFail) throw IOException("simulated failure")
        deletedSeries.add(seriesId)
    }

    fun reset() {
        deletedSeries.clear()
        shouldFail = false
    }
}
