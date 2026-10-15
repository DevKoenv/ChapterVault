package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesGenresTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.SeriesQuery
import dev.koenv.chaptervault.kernel.api.SeriesSort
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
                SchemaUtils.create(
                    UserTable,
                    SeriesTable,
                    SeriesGenresTable,
                    ChapterTable,
                    TaskTable,
                    UserSeriesProgressTable,
                )
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        mockStorage.reset()
        transaction {
            SchemaUtils.drop(
                UserSeriesProgressTable,
                TaskTable,
                ChapterTable,
                SeriesGenresTable,
                SeriesTable,
                UserTable,
            )
            SchemaUtils.create(
                UserTable,
                SeriesTable,
                SeriesGenresTable,
                ChapterTable,
                TaskTable,
                UserSeriesProgressTable,
            )
        }
    }

    @Test
    fun `removeSeries deletes chapters and progress but preserves tasks`() =
        runBlocking {
            val userId = insertUser()
            val series = (repo.addToLibrary("mangadex", "ext-cascade", autoDownload = false) as Result.Success).value
            val chapterId = insertChapter(Id.from(series.id.toString()))
            insertProgress(userId, series.id, chapterId)
            insertTask(chapterId.toString())
            insertTask(series.id.toString())

            val result = repo.removeSeries(series.id)
            assertIs<Result.Success<Unit>>(result)

            transaction {
                assertEquals(0L, ChapterTable.selectAll().where { ChapterTable.seriesId eq series.id.toString() }.count())
                assertEquals(0L, UserSeriesProgressTable.selectAll().count())
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
        runBlocking<Unit> {
            val series = (repo.addToLibrary("mangadex", "ext-del-fail", autoDownload = false) as Result.Success).value
            mockStorage.shouldFail = true

            val result = repo.removeSeries(series.id)

            assertIs<Result.Success<Unit>>(result)
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
    fun `searchLibrary treats percent and underscore in the query as plain text`() =
        runBlocking<Unit> {
            // a new series is titled with its external id until its metadata has been fetched
            repo.addToLibrary("mangadex", "50% Off", autoDownload = false)
            repo.addToLibrary("mangadex", "50 Off", autoDownload = false)
            repo.addToLibrary("mangadex", "a_b", autoDownload = false)
            repo.addToLibrary("mangadex", "axb", autoDownload = false)

            val percent = (repo.searchLibrary("50%", PageRequest()) as Result.Success).value
            val underscore = (repo.searchLibrary("a_b", PageRequest()) as Result.Success).value

            assertEquals(listOf("50% Off"), percent.items.map { it.externalId })
            assertEquals(listOf("a_b"), underscore.items.map { it.externalId })
        }

    @Test
    fun `searchLibrary treats a backslash in the query as plain text`() =
        runBlocking<Unit> {
            repo.addToLibrary("mangadex", "back\\slash", autoDownload = false)
            repo.addToLibrary("mangadex", "backslash", autoDownload = false)

            val result = (repo.searchLibrary("back\\s", PageRequest()) as Result.Success).value

            assertEquals(listOf("back\\slash"), result.items.map { it.externalId })
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
        runBlocking<Unit> {
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

    @Test
    fun `getSeries returns genres after updateEnrichedFields`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-genres", autoDownload = false) as Result.Success).value
            repo.updateEnrichedFields(series.id, null, null, null, null, listOf("Action", "Comedy"))

            val result = repo.getSeries(series.id)
            val fetched = (result as Result.Success).value
            assertEquals(listOf("Action", "Comedy").sorted(), fetched.genres.sorted())
        }

    @Test
    fun `listSeries returns genres for each series`() =
        runBlocking {
            val s1 = (repo.addToLibrary("mangadex", "ext-g1", autoDownload = false) as Result.Success).value
            val s2 = (repo.addToLibrary("mangadex", "ext-g2", autoDownload = false) as Result.Success).value
            repo.updateEnrichedFields(s1.id, null, null, null, null, listOf("Drama"))
            repo.updateEnrichedFields(s2.id, null, null, null, null, listOf("Horror", "Thriller"))

            val page = (repo.listSeries(PageRequest()) as Result.Success).value
            val byId = page.items.associateBy { it.externalId }
            assertEquals(listOf("Drama"), byId["ext-g1"]!!.genres)
            assertEquals(listOf("Horror", "Thriller").sorted(), byId["ext-g2"]!!.genres.sorted())
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

    @Test
    fun `listChaptersPaginated reports an unknown series as not found`() =
        runBlocking<Unit> {
            val result = repo.listChaptersPaginated(Id.generate(), null, PageRequest(0, 10))

            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }

    private fun addTitled(titles: List<String>): List<Id> =
        titles.mapIndexed { i, title ->
            val series = runBlocking { (repo.addToLibrary("mangadex", "ext-order-$i", autoDownload = false) as Result.Success).value }
            transaction { SeriesTable.update({ SeriesTable.id eq series.id.toString() }) { it[SeriesTable.title] = title } }
            series.id
        }

    private fun <T> pages(fetch: (PageRequest) -> List<T>): List<List<T>> = (0..2).map { fetch(PageRequest(it, 2)) }

    @Test
    fun `listSeries pages are ordered by title so no row repeats or is skipped`() =
        runBlocking {
            addTitled(listOf("C", "A", "E", "B", "D"))

            val pages = pages { runBlocking { (repo.listSeries(it) as Result.Success).value.items.map { s -> s.title } } }

            assertEquals(listOf(listOf("A", "B"), listOf("C", "D"), listOf("E")), pages)
        }

    @Test
    fun `searchLibrary pages are ordered by title`() =
        runBlocking {
            addTitled(listOf("x3", "x1", "x5", "x2", "x4"))

            val pages = pages { runBlocking { (repo.searchLibrary("x", it) as Result.Success).value.items.map { s -> s.title } } }

            assertEquals(listOf(listOf("x1", "x2"), listOf("x3", "x4"), listOf("x5")), pages)
        }

    @Test
    fun `listSeriesByIds pages are ordered by title`() =
        runBlocking {
            val ids = addTitled(listOf("C", "A", "E", "B", "D"))

            val pages = pages { runBlocking { (repo.listSeriesByIds(ids, it) as Result.Success).value.items.map { s -> s.title } } }

            assertEquals(listOf(listOf("A", "B"), listOf("C", "D"), listOf("E")), pages)
        }

    private fun addSeries(
        title: String,
        addedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        language: String = "en",
        upstreamStatus: String? = null,
        genres: List<String> = emptyList(),
    ): Id {
        val series = runBlocking { (repo.addToLibrary("mangadex", "ext-${Id.generate()}", autoDownload = false) as Result.Success).value }
        transaction {
            SeriesTable.update({ SeriesTable.id eq series.id.toString() }) {
                it[SeriesTable.title] = title
                it[SeriesTable.addedAt] = addedAt.toKotlinInstant()
                it[SeriesTable.language] = language
                it[SeriesTable.upstreamStatus] = upstreamStatus
            }
            genres.forEach { genre ->
                SeriesGenresTable.insert {
                    it[SeriesGenresTable.seriesId] = series.id.toString()
                    it[SeriesGenresTable.genre] = genre
                }
            }
        }
        return series.id
    }

    private suspend fun titles(query: SeriesQuery): List<String> =
        (repo.listSeries(query, PageRequest()) as Result.Success).value.items.map { it.title }

    @Test
    fun `titles are sorted without regard to case`() =
        runBlocking {
            listOf("banana", "Apple", "cherry", "Date").forEach { addSeries(it) }

            assertEquals(listOf("Apple", "banana", "cherry", "Date"), titles(SeriesQuery()))
            assertEquals(listOf("Date", "cherry", "banana", "Apple"), titles(SeriesQuery(descending = true)))
        }

    @Test
    fun `sorting by date added puts the newest first unless ascending is asked for`() =
        runBlocking {
            val day = Instant.parse("2026-01-01T00:00:00Z")
            addSeries("old", addedAt = day)
            addSeries("new", addedAt = day.plusSeconds(7200))
            addSeries("mid", addedAt = day.plusSeconds(3600))

            assertEquals(listOf("new", "mid", "old"), titles(SeriesQuery(sort = SeriesSort.ADDED)))
            assertEquals(listOf("old", "mid", "new"), titles(SeriesQuery(sort = SeriesSort.ADDED, descending = false)))
        }

    @Test
    fun `pages sorted by date added stay stable when series were added at the same moment`() =
        runBlocking {
            repeat(5) { addSeries("same-$it") }

            val ids =
                (0..2).flatMap {
                    (repo.listSeries(SeriesQuery(sort = SeriesSort.ADDED), PageRequest(it, 2)) as Result.Success).value.items.map { s ->
                        s.id.toString()
                    }
                }

            assertEquals(5, ids.toSet().size)
            assertEquals(ids.sorted(), ids)
        }

    @Test
    fun `genre, language and upstream status filters each match their series and combine`() =
        runBlocking {
            addSeries("match", language = "ja", upstreamStatus = "ONGOING", genres = listOf("Action", "Drama"))
            addSeries("other genre", language = "ja", upstreamStatus = "ONGOING", genres = listOf("Romance"))
            addSeries("other language", language = "en", upstreamStatus = "ONGOING", genres = listOf("Action"))
            addSeries("other status", language = "ja", upstreamStatus = "COMPLETED", genres = listOf("Action"))

            assertEquals(listOf("match", "other language", "other status"), titles(SeriesQuery(genre = "Action")))
            assertEquals(listOf("match", "other genre", "other status"), titles(SeriesQuery(language = "ja")))
            assertEquals(listOf("other status"), titles(SeriesQuery(upstreamStatus = "COMPLETED")))
            val page =
                (
                    repo.listSeries(
                        SeriesQuery(genre = "Action", language = "ja", upstreamStatus = "ONGOING"),
                        PageRequest(),
                    ) as Result.Success
                ).value
            assertEquals(listOf("match"), page.items.map { it.title })
            assertEquals(1L, page.totalItems)
        }

    @Test
    fun `ids and title filters combine with a genre`() =
        runBlocking {
            val alphaOne = addSeries("Alpha one", genres = listOf("Action", "Adventure"))
            val alphaTwo = addSeries("Alpha two", genres = listOf("Romance"))
            addSeries("Beta", genres = listOf("Action"))

            assertEquals(listOf("Alpha one"), titles(SeriesQuery(ids = listOf(alphaOne, alphaTwo), genre = "Action")))
            assertEquals(listOf("Alpha one"), titles(SeriesQuery(titleContains = "alpha", genre = "Action")))
            assertEquals(emptyList(), titles(SeriesQuery(ids = emptyList())))
        }

    @Test
    fun `listChaptersPaginated breaks ties on the chapter index by id`() =
        runBlocking {
            val series = (repo.addToLibrary("mangadex", "ext-ties", autoDownload = false) as Result.Success).value
            repeat(6) { insertChapter(series.id) }

            val paged =
                (0..2).flatMap {
                    (repo.listChaptersPaginated(series.id, null, PageRequest(it, 2)) as Result.Success).value.items.map { c ->
                        c.id.toString()
                    }
                }

            assertEquals(paged.sorted(), paged)
            assertEquals(6, paged.toSet().size)
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
        seriesId: Id,
        chapterId: Id,
    ) {
        transaction {
            UserSeriesProgressTable.insert {
                it[UserSeriesProgressTable.userId] = userId.toString()
                it[UserSeriesProgressTable.seriesId] = seriesId.toString()
                it[UserSeriesProgressTable.latestChapterId] = chapterId.toString()
                it[UserSeriesProgressTable.latestPage] = 0
                it[UserSeriesProgressTable.readAt] = Instant.now().toKotlinInstant()
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
