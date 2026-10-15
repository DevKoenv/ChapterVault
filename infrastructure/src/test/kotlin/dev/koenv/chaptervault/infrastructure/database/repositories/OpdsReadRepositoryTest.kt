package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesGenresTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpdsReadRepositoryTest {
    private val repo = OpdsReadRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("opds-read-repo-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect(
                "jdbc:sqlite:${dbFile.absolutePath}",
                driver = "org.sqlite.JDBC",
                setupConnection = { it.createStatement().execute("PRAGMA foreign_keys = ON") },
            )
            transaction {
                SchemaUtils.create(UserTable, SeriesTable, SeriesGenresTable, ChapterTable, UserSeriesProgressTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(UserSeriesProgressTable, ChapterTable, SeriesGenresTable, SeriesTable, UserTable)
            SchemaUtils.create(UserTable, SeriesTable, SeriesGenresTable, ChapterTable, UserSeriesProgressTable)
        }
    }

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

    private fun insertSeries(
        id: Id = Id.generate(),
        title: String = "Test Series",
        language: String = "en",
        upstreamStatus: String? = null,
        addedAt: Instant = Instant.now(),
    ): Id {
        transaction {
            SeriesTable.insert {
                it[SeriesTable.id] = id.toString()
                it[SeriesTable.title] = title
                it[SeriesTable.connectorId] = "test"
                it[SeriesTable.externalId] = "ext-$id"
                it[SeriesTable.status] = SeriesStatus.IN_LIBRARY.name
                it[SeriesTable.autoDownload] = false
                it[SeriesTable.language] = language
                it[SeriesTable.upstreamStatus] = upstreamStatus
                it[SeriesTable.addedAt] = addedAt.toKotlinInstant()
                it[SeriesTable.updatedAt] = addedAt.toKotlinInstant()
            }
        }
        return id
    }

    private fun insertChapter(
        seriesId: Id,
        id: Id = Id.generate(),
        index: Double = 1.0,
        addedAt: Instant = Instant.now(),
    ): Id {
        transaction {
            ChapterTable.insert {
                it[ChapterTable.id] = id.toString()
                it[ChapterTable.seriesId] = seriesId.toString()
                it[ChapterTable.title] = "Chapter $index"
                it[ChapterTable.chapterIndex] = index
                it[ChapterTable.externalId] = "ext-ch-$id"
                it[ChapterTable.downloadStatus] = DownloadStatus.PENDING.name
                it[ChapterTable.addedAt] = addedAt.toKotlinInstant()
                it[ChapterTable.updatedAt] = addedAt.toKotlinInstant()
            }
        }
        return id
    }

    private fun insertProgress(
        userId: Id,
        seriesId: Id,
        latestChapterId: Id,
        readAt: Instant = Instant.now(),
    ) {
        transaction {
            UserSeriesProgressTable.upsert {
                it[UserSeriesProgressTable.userId] = userId.toString()
                it[UserSeriesProgressTable.seriesId] = seriesId.toString()
                it[UserSeriesProgressTable.latestChapterId] = latestChapterId.toString()
                it[UserSeriesProgressTable.latestPage] = 0
                it[UserSeriesProgressTable.readAt] = readAt.toKotlinInstant()
            }
        }
    }

    private fun insertGenre(
        seriesId: Id,
        genre: String,
    ) {
        transaction {
            SeriesGenresTable.insert {
                it[SeriesGenresTable.seriesId] = seriesId.toString()
                it[SeriesGenresTable.genre] = genre
            }
        }
    }

    @Test
    fun `listReadHistory returns empty pagination when user has no read history`() =
        runBlocking {
            val userId = insertUser()
            val result = repo.listReadHistory(userId, PageRequest())
            assertIs<Result.Success<*>>(result)
            val page = (result as Result.Success).value
            assertEquals(0L, page.totalItems)
            assertTrue(page.items.isEmpty())
        }

    @Test
    fun `listReadHistory returns series with chaptersRead and totalChapters`() =
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            val ch2 = insertChapter(seriesId, index = 2.0)
            insertChapter(seriesId, index = 3.0) // not read
            insertProgress(userId, seriesId, ch1)
            insertProgress(userId, seriesId, ch2) // latest = ch2 (index 2.0), so 2 chapters read

            val result = repo.listReadHistory(userId, PageRequest())
            assertIs<Result.Success<*>>(result)
            val page = (result as Result.Success).value
            assertEquals(1L, page.totalItems)
            assertEquals(1, page.items.size)
            assertEquals(2, page.items[0].chaptersRead)
            assertEquals(3, page.items[0].totalChapters)
        }

    @Test
    fun `listReadHistory orders by most recently read`() =
        runBlocking {
            val userId = insertUser()
            val series1 = insertSeries(title = "First")
            val series2 = insertSeries(title = "Second")
            val ch1 = insertChapter(series1)
            val ch2 = insertChapter(series2)
            val earlier = Instant.now().minusSeconds(3600)
            val later = Instant.now()
            insertProgress(userId, series1, ch1, earlier)
            insertProgress(userId, series2, ch2, later)

            val page = (repo.listReadHistory(userId, PageRequest()) as Result.Success).value
            assertEquals("Second", page.items[0].series.title)
            assertEquals("First", page.items[1].series.title)
        }

    @Test
    fun `listReadHistory is isolated per user`() =
        runBlocking {
            val user1 = insertUser()
            val user2 = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)
            insertProgress(user1, seriesId, chapterId)

            val page1 = (repo.listReadHistory(user1, PageRequest()) as Result.Success).value
            val page2 = (repo.listReadHistory(user2, PageRequest()) as Result.Success).value
            assertEquals(1L, page1.totalItems)
            assertEquals(0L, page2.totalItems)
        }

    @Test
    fun `listRecentlyAddedChapters returns chapters ordered by addedAt desc`() =
        runBlocking {
            val seriesId = insertSeries()
            val older = Instant.now().minusSeconds(3600)
            val newer = Instant.now()
            insertChapter(seriesId, index = 1.0, addedAt = older)
            insertChapter(seriesId, index = 2.0, addedAt = newer)

            val page = (repo.listRecentlyAddedChapters(PageRequest()) as Result.Success).value
            assertEquals(2L, page.totalItems)
            assertEquals(2.0, page.items[0].chapter.chapterIndex)
            assertEquals(1.0, page.items[1].chapter.chapterIndex)
        }

    @Test
    fun `listRecentlyAddedChapters includes series data`() =
        runBlocking {
            val seriesId = insertSeries(title = "My Manga")
            insertChapter(seriesId)

            val page = (repo.listRecentlyAddedChapters(PageRequest()) as Result.Success).value
            assertEquals(1, page.items.size)
            assertEquals("My Manga", page.items[0].series.title)
        }

    @Test
    fun `listGenres returns genres with series counts`() =
        runBlocking {
            val s1 = insertSeries()
            val s2 = insertSeries()
            val s3 = insertSeries()
            insertGenre(s1, "Action")
            insertGenre(s2, "Action")
            insertGenre(s3, "Romance")

            val genres = (repo.listGenres() as Result.Success).value
            val actionCount = genres.first { it.genre == "Action" }.seriesCount
            val romanceCount = genres.first { it.genre == "Romance" }.seriesCount
            assertEquals(2, actionCount)
            assertEquals(1, romanceCount)
            // ordered by count desc
            assertEquals("Action", genres[0].genre)
        }

    @Test
    fun `listLanguages returns languages with series counts`() =
        runBlocking {
            insertSeries(language = "en")
            insertSeries(language = "en")
            insertSeries(language = "ja")

            val languages = (repo.listLanguages() as Result.Success).value
            val en = languages.first { it.language == "en" }.seriesCount
            val ja = languages.first { it.language == "ja" }.seriesCount
            assertEquals(2, en)
            assertEquals(1, ja)
        }

    @Test
    fun `listUpstreamStatuses returns known statuses with counts`() =
        runBlocking {
            insertSeries(upstreamStatus = "ONGOING")
            insertSeries(upstreamStatus = "ONGOING")
            insertSeries(upstreamStatus = "COMPLETED")
            insertSeries() // null upstreamStatus — excluded

            val statuses = (repo.listUpstreamStatuses() as Result.Success).value
            assertEquals(2, statuses.size)
            val ongoing = statuses.first { it.status == "ONGOING" }.seriesCount
            assertEquals(2, ongoing)
        }
}
