package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.extensions.connectors.Bucket
import dev.koenv.chaptervault.extensions.connectors.BucketConfig
import dev.koenv.chaptervault.extensions.connectors.BucketKey
import dev.koenv.chaptervault.extensions.connectors.ChapterMetadata
import dev.koenv.chaptervault.extensions.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.DownloadPage
import dev.koenv.chaptervault.extensions.connectors.DownloadResult
import dev.koenv.chaptervault.extensions.connectors.HttpConnector
import dev.koenv.chaptervault.extensions.connectors.SeriesMetadata
import dev.koenv.chaptervault.extensions.connectors.SeriesSearchResult
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.CbzWriter
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
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
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TaskExecutorServiceTest {

    private val chapterRepository = ChapterRepository()
    private val taskRepository = TaskRepository()
    private val fileStorage = FileStorage(
        Files.createTempDirectory("executor-test-storage"),
        Files.createTempDirectory("executor-test-thumbnails"),
        ArchiveWriterSelector(listOf(CbzWriter())),
    )
    private val seriesRepository = SeriesRepository(fileStorage)
    private val taskQueue = InMemoryTaskQueue()
    private val registry = DefaultConnectorRegistry()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setupDb() {
            val dbFile = Files.createTempFile("executor-test", ".sqlite").toFile()
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
                it[SeriesTable.connectorId] = "test-connector"
                it[SeriesTable.externalId] = "ext-001"
                it[SeriesTable.status] = SeriesStatus.IN_LIBRARY.name
                it[SeriesTable.autoDownload] = false
                it[SeriesTable.defaultFormat] = null
                it[SeriesTable.coverUrl] = null
                it[SeriesTable.description] = null
                it[SeriesTable.language] = "en"
                it[SeriesTable.addedAt] = Instant.now().toKotlinInstant()
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }
        return id
    }

    private suspend fun insertChapter(seriesId: Id): Chapter {
        return (chapterRepository.insertChapter(seriesId, "Chapter 1", 1.0, "ext-ch-001") as Result.Success).value
    }

    private fun makeTask(chapterId: Id, connectorId: String): Task = Task(
        id = Id.generate(),
        type = TaskType.DOWNLOAD_CHAPTER,
        status = TaskStatus.PENDING,
        targetType = TargetType.CHAPTER,
        targetId = chapterId,
        payload = mapOf(
            "connectorId" to connectorId,
            "chapterId" to chapterId.toString(),
            "format" to "Cbz",
        ),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun makeConnector(
        connectorId: String,
        pages: List<DownloadPage>,
        fetchPageResult: (DownloadPage) -> Result<ByteArray> = { Result.Success(ByteArray(10) { i -> i.toByte() }) },
    ): HttpConnector {
        return object : HttpConnector(HttpClient()) {
            override val id = connectorId
            override val name = "Test Connector"
            override val bucketConfigs: Map<BucketKey, BucketConfig> = mapOf(
                Bucket.CDN to BucketConfig(requestsPerSecond = 100.0),
            )
            override suspend fun fetchPage(page: DownloadPage): Result<ByteArray> = fetchPageResult(page)
            override suspend fun search(query: String, request: PageRequest): Result<Pagination<SeriesSearchResult>> = TODO()
            override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> = TODO()
            override suspend fun fetchChapters(externalId: String, language: String): Result<List<ChapterMetadata>> = TODO()
            override suspend fun download(chapter: Chapter, format: ChapterFormat): Result<DownloadResult> =
                Result.Success(DownloadResult(pages = pages))
        }
    }

    private fun makeExecutor(tempDir: java.nio.file.Path): TaskExecutorService {
        val fileStorage = FileStorage(tempDir, Files.createTempDirectory("executor-test-thumbnails"), ArchiveWriterSelector(listOf(CbzWriter())))
        return TaskExecutorService(taskQueue, taskRepository, registry, seriesRepository, chapterRepository, fileStorage, HttpClient(), InMemoryEventBus())
    }

    @Test
    fun `handleDownloadChapter writes CBZ file and marks chapter DOWNLOADED`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("executor-test-storage")
            val executor = makeExecutor(tempDir)

            val seriesId = insertSeries()
            val chapter = insertChapter(seriesId)

            val pages = List(3) { i -> DownloadPage(url = "https://cdn.example.com/p$i.jpg", index = i) }
            val connector = makeConnector("dl-connector", pages)
            registry.register(connector)

            val result = executor.handleDownloadChapter(makeTask(chapter.id, "dl-connector"))

            assertIs<Result.Success<Unit>>(result)

            val updated = (chapterRepository.getChapter(chapter.id) as Result.Success).value
            assertEquals(DownloadStatus.DOWNLOADED, updated.downloadStatus)

            val file = tempDir.resolve(chapter.seriesId.toString()).resolve("${chapter.id}.cbz")
            assert(Files.exists(file)) { "CBZ file was not written to disk at $file" }
        }
    }

    @Test
    fun `handleDownloadChapter returns failure when connector is not registered`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("executor-test-storage")
            val executor = makeExecutor(tempDir)

            val seriesId = insertSeries()
            val chapter = insertChapter(seriesId)

            val result = executor.handleDownloadChapter(makeTask(chapter.id, "missing-connector"))

            assertIs<Result.Failure>(result)
        }
    }

    @Test
    fun `handleDownloadChapter marks chapter FAILED when connector returns 0 pages`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("executor-test-storage")
            val executor = makeExecutor(tempDir)

            val seriesId = insertSeries()
            val chapter = insertChapter(seriesId)

            val connector = makeConnector("zero-pages-connector", emptyList())
            registry.register(connector)

            val result = executor.handleDownloadChapter(makeTask(chapter.id, "zero-pages-connector"))

            assertIs<Result.Failure>(result)

            val updated = (chapterRepository.getChapter(chapter.id) as Result.Success).value
            assertEquals(DownloadStatus.FAILED, updated.downloadStatus)
        }
    }

    @Test
    fun `handleDownloadChapter marks chapter FAILED and writes no file when any page fetch fails`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("executor-test-storage")
            val executor = makeExecutor(tempDir)

            val seriesId = insertSeries()
            val chapter = insertChapter(seriesId)

            val pages = List(3) { i -> DownloadPage(url = "https://cdn.example.com/p$i.jpg", index = i) }
            val connector = makeConnector("partial-fail-connector", pages) { page ->
                if (page.index == 1)
                    Result.Failure(AppError.InternalError("simulated fetch failure for page 1"))
                else
                    Result.Success(ByteArray(10) { i -> i.toByte() })
            }
            registry.register(connector)

            val result = executor.handleDownloadChapter(makeTask(chapter.id, "partial-fail-connector"))

            assertIs<Result.Failure>(result)

            val updated = (chapterRepository.getChapter(chapter.id) as Result.Success).value
            assertEquals(DownloadStatus.FAILED, updated.downloadStatus)

            val file = tempDir.resolve(chapter.seriesId.toString()).resolve(chapter.id.toString())
            assertFalse(Files.exists(file), "No file should be written when a page fetch fails")
        }
    }

    @Test
    fun `handleDownloadChapter sorts out-of-order pages by index before writing`() {
        runBlocking {
            val tempDir = Files.createTempDirectory("executor-test-storage")
            val executor = makeExecutor(tempDir)

            val seriesId = insertSeries()
            val chapter = insertChapter(seriesId)

            // Connector returns pages in reverse order — executor must sort them
            val pages = listOf(
                DownloadPage(url = "https://cdn.example.com/p2.jpg", index = 2),
                DownloadPage(url = "https://cdn.example.com/p0.jpg", index = 0),
                DownloadPage(url = "https://cdn.example.com/p1.jpg", index = 1),
            )
            val connector = makeConnector("unsorted-connector", pages)
            registry.register(connector)

            val result = executor.handleDownloadChapter(makeTask(chapter.id, "unsorted-connector"))

            assertIs<Result.Success<Unit>>(result)

            val updated = (chapterRepository.getChapter(chapter.id) as Result.Success).value
            assertEquals(DownloadStatus.DOWNLOADED, updated.downloadStatus)
        }
    }
}
