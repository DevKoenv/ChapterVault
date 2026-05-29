package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
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

class BookmarkRepositoryTest {
    private val repo = BookmarkRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("bookmark-repo-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(UserTable, SessionTable, SeriesTable, ChapterTable, ProgressTable, BookmarkTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(BookmarkTable, ProgressTable, ChapterTable, SeriesTable, SessionTable, UserTable)
            SchemaUtils.create(UserTable, SessionTable, SeriesTable, ChapterTable, ProgressTable, BookmarkTable)
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
    fun `create returns the new bookmark`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            val result = repo.create(userId, chapterId, page = 5)
            assertIs<Result.Success<*>>(result)
            val bookmark = (result as Result.Success).value
            assertEquals(chapterId, bookmark.chapterId)
            assertEquals(5, bookmark.page)
        }
    }

    @Test
    fun `list returns bookmarks for the series ordered by chapter then page`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val ch1 = insertChapter(seriesId, index = 1.0)
            val ch2 = insertChapter(seriesId, index = 2.0)

            repo.create(userId, ch2, page = 3)
            repo.create(userId, ch1, page = 10)
            repo.create(userId, ch1, page = 2)

            val result = repo.list(userId, seriesId)
            assertIs<Result.Success<*>>(result)
            val bookmarks = (result as Result.Success).value
            assertEquals(3, bookmarks.size)
            assertEquals(ch1, bookmarks[0].chapterId)
            assertEquals(2, bookmarks[0].page)
            assertEquals(ch1, bookmarks[1].chapterId)
            assertEquals(10, bookmarks[1].page)
            assertEquals(ch2, bookmarks[2].chapterId)
            assertEquals(3, bookmarks[2].page)
        }
    }

    @Test
    fun `list returns empty for series with no bookmarks`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()

            val result = repo.list(userId, seriesId)
            assertIs<Result.Success<*>>(result)
            assertTrue((result as Result.Success).value.isEmpty())
        }
    }

    @Test
    fun `list is scoped to the requesting user`() {
        runBlocking {
            val user1 = insertUser()
            val user2 = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)

            repo.create(user1, chapterId, page = 7)

            val r1 = (repo.list(user1, seriesId) as Result.Success).value
            val r2 = (repo.list(user2, seriesId) as Result.Success).value
            assertEquals(1, r1.size)
            assertTrue(r2.isEmpty())
        }
    }

    @Test
    fun `delete removes the bookmark`() {
        runBlocking {
            val userId = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)
            val bookmark = (repo.create(userId, chapterId, page = 1) as Result.Success).value

            val result = repo.delete(userId, bookmark.id)
            assertIs<Result.Success<Unit>>(result)

            val remaining = (repo.list(userId, seriesId) as Result.Success).value
            assertTrue(remaining.isEmpty())
        }
    }

    @Test
    fun `delete returns NotFound for unknown bookmark`() {
        runBlocking {
            val userId = insertUser()
            val result = repo.delete(userId, Id.generate())
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }

    @Test
    fun `delete cannot remove another user bookmark`() {
        runBlocking {
            val owner = insertUser()
            val other = insertUser()
            val seriesId = insertSeries()
            val chapterId = insertChapter(seriesId)
            val bookmark = (repo.create(owner, chapterId, page = 1) as Result.Success).value

            val result = repo.delete(other, bookmark.id)
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }
    }
}
