package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.enricher.DefaultMetadataEnricherRegistry
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.kernel.connector.BucketConfig
import dev.koenv.chaptervault.kernel.connector.BucketKey
import dev.koenv.chaptervault.kernel.connector.ChapterMetadata
import dev.koenv.chaptervault.kernel.connector.DownloadResult
import dev.koenv.chaptervault.kernel.connector.HttpConnector
import dev.koenv.chaptervault.kernel.connector.SeriesMetadata
import dev.koenv.chaptervault.kernel.connector.SeriesSearchResult
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskExecutorCoverTest {
    private lateinit var dir: Path
    private lateinit var fileStorage: FileStorage
    private lateinit var seriesRepository: SeriesRepository
    private val registry = DefaultConnectorRegistry()

    @BeforeEach
    fun setUp(
        @TempDir tempDir: Path,
    ) {
        dir = tempDir
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("cover.db")}"))
        fileStorage = FileStorage(dir.resolve("lib"), dir.resolve("thumbs"), ArchiveWriterSelector(emptyList()))
        seriesRepository = SeriesRepository(fileStorage)
        registry.register(
            object : HttpConnector(HttpClient()) {
                override val id = "src"
                override val name = "src"
                override val bucketConfigs: Map<BucketKey, BucketConfig> = mapOf(Bucket.CDN to BucketConfig(requestsPerSecond = 100.0))

                override suspend fun search(
                    query: String,
                    request: PageRequest,
                ): Result<Pagination<SeriesSearchResult>> = TODO()

                override suspend fun fetchSeries(externalId: String) =
                    Result.Success(SeriesMetadata(externalId, "Title", "https://img.example/cover.jpg", "About"))

                override suspend fun fetchChapters(
                    externalId: String,
                    language: String,
                ): Result<List<ChapterMetadata>> = TODO()

                override suspend fun download(
                    chapter: Chapter,
                    format: ChapterFormat,
                ): Result<DownloadResult> = TODO()
            },
        )
    }

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private fun fetchMetadata(coverBytes: ByteArray): Pair<Id, String?> =
        runBlocking {
            val series = (seriesRepository.addToLibrary("src", "ext-1", "en", false) as Result.Success).value
            val executor =
                TaskExecutorService(
                    taskQueue = InMemoryTaskQueue(),
                    taskRepository = TaskRepository(),
                    connectorRegistry = registry,
                    seriesRepository = seriesRepository,
                    chapterRepository = ChapterRepository(),
                    fileStorage = fileStorage,
                    httpClient = HttpClient(MockEngine { respond(coverBytes, HttpStatusCode.OK) }),
                    eventBus = InMemoryEventBus(),
                    enricherRegistry = DefaultMetadataEnricherRegistry(),
                    settings = ExecutorSettings(maxCoverBytes = 1024),
                )
            val task =
                Task(
                    id = Id.generate(),
                    type = TaskType.FETCH_SERIES_METADATA,
                    status = TaskStatus.PENDING,
                    targetType = TargetType.SERIES,
                    targetId = series.id,
                    payload = mapOf("connectorId" to "src", "externalId" to "ext-1", "language" to "en"),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            assertTrue(executor.handleFetchSeriesMetadata(task) is Result.Success)
            series.id to (seriesRepository.getSeries(series.id) as Result.Success).value.coverUrl
        }

    @Test
    fun `a stored cover is linked through the public api path`() {
        val (id, coverUrl) = fetchMetadata(png())

        assertEquals("/api/v1/library/series/$id/cover", coverUrl)
        assertTrue(dir.resolve("thumbs").resolve("$id.jpg").exists())
    }

    @Test
    fun `a cover over the size limit is not downloaded and the remote url is kept`() {
        val (id, coverUrl) = fetchMetadata(ByteArray(5000))

        assertEquals("https://img.example/cover.jpg", coverUrl)
        assertFalse(dir.resolve("thumbs").resolve("$id.jpg").exists())
    }

    @Test
    fun `a cover that is not an image is not stored and the remote url is kept`() {
        val (id, coverUrl) = fetchMetadata("<html>not an image</html>".toByteArray())

        assertEquals("https://img.example/cover.jpg", coverUrl)
        assertFalse(dir.resolve("thumbs").resolve("$id.jpg").exists())
    }
}
