package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.SeriesStatus
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
import kotlin.test.assertNull

class ProgressRepositoryTest {
    private val repo = ProgressRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("progress-repo-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(UserTable, SessionTable, SeriesTable, ChapterTable, UserSeriesProgressTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(UserSeriesProgressTable, ChapterTable, SeriesTable, SessionTable, UserTable)
            SchemaUtils.create(UserTable, SessionTable, SeriesTable, ChapterTable, UserSeriesProgressTable)
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

    private fun insertChapter(
        seriesId: Id,
        id: Id = Id.generate(),
        index: Double = 1.0,
    ): Id {
        transaction {
            ChapterTable.insert {
                it[ChapterTable.id] = id.toString()
                it[ChapterTable.seriesId] = seriesId.toString()
                it[ChapterTable.title] = "Chapter $index"
                it[ChapterTable.chapterIndex] = index
                it[ChapterTable.externalId] = "ext-ch-$id"
                it[ChapterTable.downloadStatus] = DownloadStatus.PENDING.name
                it[ChapterTable.addedAt] = Instant.now().toKotlinInstant()
                it[ChapterTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }
        return id
    }

    @Test
    fun `markRead sets latest chapter`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            val result = repo.markRead(userId, chapterId)
            assertIs<Result.Success<Unit>>(result)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(chapterId, progress.latestChapterId)
            assertEquals(1, progress.totalCount)
            assertEquals(0, progress.unreadCount)
        }
    }

    @Test
    fun `markRead is idempotent`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            repo.markRead(userId, chapterId)
            val result = repo.markRead(userId, chapterId)
            assertIs<Result.Success<Unit>>(result)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(chapterId, progress.latestChapterId)
        }
    }

    @Test
    fun `markRead advances to higher chapter`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            val ch2 = insertChapter(seriesId, index = 2.0)

            repo.markRead(userId, ch1)
            repo.markRead(userId, ch2)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(ch2, progress.latestChapterId)
        }
    }

    @Test
    fun `markRead does not regress to lower chapter`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            val ch2 = insertChapter(seriesId, index = 2.0)

            repo.markRead(userId, ch2)
            repo.markRead(userId, ch1)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(ch2, progress.latestChapterId)
        }
    }

    @Test
    fun `markUnread clears progress when latest chapter`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            repo.markRead(userId, chapterId)
            val result = repo.markUnread(userId, chapterId)
            assertIs<Result.Success<Unit>>(result)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertNull(progress.latestChapterId)
            assertEquals(1, progress.unreadCount)
        }
    }

    @Test
    fun `markUnread on non-latest chapter is no-op`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            val ch2 = insertChapter(seriesId, index = 2.0)

            repo.markRead(userId, ch2)
            val result = repo.markUnread(userId, ch1)
            assertIs<Result.Success<Unit>>(result)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(ch2, progress.latestChapterId)
        }
    }

    @Test
    fun `markUnread on unread series is a no-op`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            val result = repo.markUnread(userId, chapterId)
            assertIs<Result.Success<Unit>>(result)
        }
    }

    @Test
    fun `getProgress returns correct unread count`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            insertChapter(seriesId, index = 2.0)
            insertChapter(seriesId, index = 3.0)

            repo.markRead(userId, ch1)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(seriesId, progress.seriesId)
            assertEquals(ch1, progress.latestChapterId)
            assertEquals(2, progress.unreadCount)
            assertEquals(3, progress.totalCount)
        }
    }

    @Test
    fun `getProgress is per-user`() {
        runBlocking {
            val user1 = insertUser()
            val user2 = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            repo.markRead(user1, chapterId)

            val p1 = (repo.getProgress(user1, seriesId) as Result.Success).value
            val p2 = (repo.getProgress(user2, seriesId) as Result.Success).value
            assertEquals(chapterId, p1.latestChapterId)
            assertNull(p2.latestChapterId)
        }
    }

    @Test
    fun `getProgress returns all unread for series with no progress`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            insertChapter(seriesId)

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertNull(progress.latestChapterId)
            assertEquals(1, progress.unreadCount)
            assertEquals(1, progress.totalCount)
        }
    }

    @Test
    fun `getProgress returns zeros for series with no chapters`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()

            val progress = (repo.getProgress(userId, seriesId) as Result.Success).value
            assertEquals(0, progress.unreadCount)
            assertEquals(0, progress.totalCount)
        }
    }
}
