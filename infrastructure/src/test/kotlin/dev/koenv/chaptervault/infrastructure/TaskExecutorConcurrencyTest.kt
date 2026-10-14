package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.enricher.DefaultMetadataEnricherRegistry
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.CbzWriter
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.connector.Bucket
import dev.koenv.chaptervault.kernel.connector.BucketConfig
import dev.koenv.chaptervault.kernel.connector.BucketKey
import dev.koenv.chaptervault.kernel.connector.ChapterMetadata
import dev.koenv.chaptervault.kernel.connector.DownloadPage
import dev.koenv.chaptervault.kernel.connector.DownloadResult
import dev.koenv.chaptervault.kernel.connector.HttpConnector
import dev.koenv.chaptervault.kernel.connector.SeriesMetadata
import dev.koenv.chaptervault.kernel.connector.SeriesSearchResult
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.runtime.EventPublishingTaskQueue
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskExecutorConcurrencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: Path
    private val taskRepository = TaskRepository()
    private val chapterRepository = ChapterRepository()
    private lateinit var seriesRepository: SeriesRepository
    private lateinit var fileStorage: FileStorage
    private val registry = DefaultConnectorRegistry()
    private val eventBus = InMemoryEventBus()
    private val startedExecutors = mutableListOf<Job>()

    @BeforeEach
    fun setUp(
        @TempDir tempDir: Path,
    ) {
        dir = tempDir
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("executor.db")}"))
        fileStorage = FileStorage(dir.resolve("lib"), dir.resolve("thumbs"), ArchiveWriterSelector(listOf(CbzWriter())))
        seriesRepository = SeriesRepository(fileStorage)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { startedExecutors.forEach { it.cancelAndJoin() } }
        scope.coroutineContext[Job]?.cancel()
    }

    /** One connector whose behaviour the test controls. */
    private class ScriptedConnector(
        override val id: String,
        private val onDownload: suspend (Chapter) -> Result<DownloadResult>,
    ) : HttpConnector(HttpClient()) {
        override val name = id
        override val bucketConfigs: Map<BucketKey, BucketConfig> = mapOf(Bucket.CDN to BucketConfig(requestsPerSecond = 1000.0))

        override suspend fun fetchPage(page: DownloadPage): Result<ByteArray> = Result.Success(ByteArray(16) { it.toByte() })

        override suspend fun search(
            query: String,
            request: PageRequest,
        ): Result<Pagination<SeriesSearchResult>> = TODO()

        override suspend fun fetchSeries(externalId: String): Result<SeriesMetadata> = TODO()

        override suspend fun fetchChapters(
            externalId: String,
            language: String,
        ): Result<List<ChapterMetadata>> = TODO()

        override suspend fun download(
            chapter: Chapter,
            format: ChapterFormat,
        ): Result<DownloadResult> = onDownload(chapter)
    }

    private fun okDownload(): Result<DownloadResult> = Result.Success(DownloadResult(listOf(DownloadPage("https://cdn/p0.jpg", 0))))

    private fun newQueue(): TaskQueue = PersistingTaskQueue(EventPublishingTaskQueue(InMemoryTaskQueue(), eventBus), taskRepository)

    private fun executor(
        queue: TaskQueue,
        settings: ExecutorSettings,
    ) = TaskExecutorService(
        taskQueue = queue,
        taskRepository = taskRepository,
        connectorRegistry = registry,
        seriesRepository = seriesRepository,
        chapterRepository = chapterRepository,
        fileStorage = fileStorage,
        httpClient = HttpClient(),
        eventBus = eventBus,
        enricherRegistry = DefaultMetadataEnricherRegistry(),
        settings = settings,
    )

    private fun run(executor: TaskExecutorService) {
        startedExecutors += scope.launch { executor.start() }
    }

    private suspend fun newChapter(
        externalId: String,
        title: String = "Chapter",
    ): Chapter {
        val series = (seriesRepository.addToLibrary("test", externalId, "en", false) as Result.Success).value
        return (chapterRepository.insertChapter(series.id, title, 1.0, "ch-$externalId") as Result.Success).value
    }

    private fun downloadTask(
        chapter: Chapter,
        connectorId: String,
        retryCount: Int = 0,
    ) = Task(
        id = Id.generate(),
        type = TaskType.DOWNLOAD_CHAPTER,
        status = TaskStatus.PENDING,
        targetType = TargetType.CHAPTER,
        targetId = chapter.id,
        payload = mapOf("connectorId" to connectorId, "chapterId" to chapter.id.toString(), "format" to "Cbz"),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        retryCount = retryCount,
    )

    private suspend fun statusOf(id: Id): TaskStatus = (taskRepository.findById(id) as Result.Success).value.status

    private suspend fun awaitStatus(
        id: Id,
        expected: TaskStatus,
        timeoutMs: Long = 10_000,
    ) = withTimeout(timeoutMs) {
        while (statusOf(id) != expected) delay(20)
    }

    private val fast =
        ExecutorSettings(
            maxConcurrentTasks = 2,
            maxTasksPerConnector = 1,
            taskTimeoutMs = TaskType.entries.associateWith { 400L },
            retryDelaysMs = listOf(300L, 300L, 300L),
        )

    @Test
    fun `a task that hangs is failed after its timeout and the next one still runs`() =
        runBlocking<Unit> {
            registry.register(ScriptedConnector("hang") { awaitCancellation() })
            registry.register(ScriptedConnector("ok") { okDownload() })
            val hung = downloadTask(newChapter("a"), "hang")
            val fine = downloadTask(newChapter("b"), "ok")
            val queue = newQueue()
            run(executor(queue, fast.copy(maxConcurrentTasks = 1)))

            queue.enqueue(hung)
            queue.enqueue(fine)

            awaitStatus(hung.id, TaskStatus.FAILED)
            awaitStatus(fine.id, TaskStatus.COMPLETED)
            assertTrue("Timed out" in (taskRepository.findById(hung.id) as Result.Success).value.errorMessage.orEmpty())
        }

    @Test
    fun `a hung connector does not hold up other connectors`() =
        runBlocking<Unit> {
            registry.register(ScriptedConnector("hang") { awaitCancellation() })
            registry.register(ScriptedConnector("ok") { okDownload() })
            val hung = downloadTask(newChapter("a"), "hang")
            val fine = downloadTask(newChapter("b"), "ok")
            val queue = newQueue()
            // the hang would take 60 s to time out, so finishing quickly proves the other task was not waiting for it
            run(executor(queue, fast.copy(taskTimeoutMs = TaskType.entries.associateWith { 60_000L })))

            queue.enqueue(hung)
            queue.enqueue(fine)

            awaitStatus(fine.id, TaskStatus.COMPLETED, timeoutMs = 5_000)
            // the hung task is (or is about to be) running and has not been failed or finished by anything
            awaitStatus(hung.id, TaskStatus.RUNNING, timeoutMs = 5_000)
            delay(200)
            assertEquals(TaskStatus.RUNNING, statusOf(hung.id))
        }

    @Test
    fun `cancelling a running task stops it and the task stays cancelled`() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            registry.register(
                ScriptedConnector("slow") {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
            )
            val task = downloadTask(newChapter("a"), "slow")
            val queue = newQueue()
            run(executor(queue, fast.copy(taskTimeoutMs = TaskType.entries.associateWith { 60_000L })))
            queue.enqueue(task)
            awaitStatus(task.id, TaskStatus.RUNNING)
            withTimeout(5_000) { entered.await() }

            // what SystemApiImpl.cancelTask does
            queue.cancel(task.id)
            eventBus.publish(TaskEvents.TaskCancelled(task.id, task.type, task.targetId, Instant.now()))

            withTimeout(5_000) { stopped.await() }
            delay(300)
            assertEquals(TaskStatus.CANCELLED, statusOf(task.id))
            assertEquals(
                1,
                (taskRepository.listTasks(PageRequest(0, 50)) as Result.Success).value.totalItems,
                "a cancelled task must not be retried",
            )
        }

    @Test
    fun `a failed task is retried later and the wait is stored so a restart keeps it`() =
        runBlocking<Unit> {
            val succeed = AtomicBoolean(false)
            registry.register(
                ScriptedConnector("flaky") {
                    if (succeed.get()) okDownload() else Result.Failure(AppError.InternalError("upstream down"))
                },
            )
            val task = downloadTask(newChapter("a"), "flaky")
            val firstQueue = newQueue()
            // long enough that the retry cannot become due before the "crash" below, however slow the machine is
            val slowRetry = fast.copy(retryDelaysMs = listOf(60_000L))
            val first = scope.launch { executor(firstQueue, slowRetry).start() }
            startedExecutors += first
            firstQueue.enqueue(task)
            awaitStatus(task.id, TaskStatus.FAILED)

            // the retry is saved right after the failure is recorded
            withTimeout(5_000) { while (taskRepository.listAllByStatus(TaskStatus.PENDING).isEmpty()) delay(20) }
            val retry = taskRepository.listAllByStatus(TaskStatus.PENDING).single()
            assertEquals(1, retry.retryCount)
            assertNotNull(retry.runAfter, "the retry time must be in the database, not only in a coroutine")
            assertTrue(retry.runAfter!!.isAfter(Instant.now()))

            // "crash": the first executor and its queue are gone; a new process starts from the database,
            // and by then the stored wait has passed
            first.cancelAndJoin()
            succeed.set(true)
            transaction {
                TaskTable.update({ TaskTable.id eq retry.id.toString() }) { it[runAfter] = Instant.now().plusMillis(300).toKotlinInstant() }
            }
            val secondQueue = newQueue()
            val second = executor(secondQueue, slowRetry)
            second.recoverOnBoot()
            run(second)

            awaitStatus(retry.id, TaskStatus.COMPLETED, timeoutMs = 10_000)
        }

    @Test
    fun `a connector is never asked for more downloads at once than its limit`() =
        runBlocking<Unit> {
            val current = AtomicInteger()
            val peak = AtomicInteger()
            registry.register(
                ScriptedConnector("polite") {
                    peak.accumulateAndGet(current.incrementAndGet(), ::maxOf)
                    delay(150)
                    current.decrementAndGet()
                    okDownload()
                },
            )
            val tasks = List(4) { downloadTask(newChapter("c$it"), "polite") }
            val queue = newQueue()
            run(
                executor(
                    queue,
                    fast.copy(maxConcurrentTasks = 3, maxTasksPerConnector = 1, taskTimeoutMs = TaskType.entries.associateWith { 5_000L }),
                ),
            )

            tasks.forEach { queue.enqueue(it) }

            tasks.forEach { awaitStatus(it.id, TaskStatus.COMPLETED) }
            assertEquals(1, peak.get())
        }

    @Test
    fun `different connectors do run at the same time`() =
        runBlocking<Unit> {
            val current = AtomicInteger()
            val peak = AtomicInteger()
            listOf("one", "two").forEach { name ->
                registry.register(
                    ScriptedConnector(name) {
                        peak.accumulateAndGet(current.incrementAndGet(), ::maxOf)
                        delay(300)
                        current.decrementAndGet()
                        okDownload()
                    },
                )
            }
            val a = downloadTask(newChapter("a"), "one")
            val b = downloadTask(newChapter("b"), "two")
            val queue = newQueue()
            run(executor(queue, fast.copy(taskTimeoutMs = TaskType.entries.associateWith { 5_000L })))

            queue.enqueue(a)
            queue.enqueue(b)

            awaitStatus(a.id, TaskStatus.COMPLETED)
            awaitStatus(b.id, TaskStatus.COMPLETED)
            assertEquals(2, peak.get())
        }

    @Test
    fun `health stays alive while a long task is running`() =
        runBlocking<Unit> {
            registry.register(ScriptedConnector("long") { awaitCancellation() })
            val task = downloadTask(newChapter("a"), "long")
            val queue = newQueue()
            val executor = executor(queue, fast.copy(maxConcurrentTasks = 1, taskTimeoutMs = TaskType.entries.associateWith { 60_000L }))
            run(executor)
            queue.enqueue(task)
            awaitStatus(task.id, TaskStatus.RUNNING)

            delay(2_600)

            assertTrue(executor.isAlive(), "the only worker is busy, but the executor is not dead")
        }

    @Test
    fun `a chapter that would not fit in memory is refused before it is written`() =
        runBlocking<Unit> {
            registry.register(
                ScriptedConnector("big") {
                    Result.Success(DownloadResult(listOf(DownloadPage("https://cdn/p0.jpg", 0), DownloadPage("https://cdn/p1.jpg", 1))))
                },
            )
            val chapter = newChapter("a")
            val task = downloadTask(chapter, "big")
            val executor = executor(newQueue(), fast.copy(maxChapterBytes = 20))

            // each page is 16 bytes, two pages are 32
            val result = executor.handleDownloadChapter(task)

            assertTrue(result is Result.Failure)
            assertFalse(
                dir
                    .resolve("lib")
                    .resolve(chapter.seriesId.toString())
                    .resolve("${chapter.id}.cbz")
                    .exists(),
            )
            assertEquals(
                dev.koenv.chaptervault.kernel.library.DownloadStatus.FAILED,
                (chapterRepository.getChapter(chapter.id) as Result.Success).value.downloadStatus,
            )
        }
}
