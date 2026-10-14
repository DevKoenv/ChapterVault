package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.ApiPaths
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.connector.readBytesBounded
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.NewChaptersDiscovered
import dev.koenv.chaptervault.kernel.event.on
import dev.koenv.chaptervault.kernel.extension.EnricherInput
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.ChapterEvents
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import dev.koenv.chaptervault.kernel.runtime.TaskPayloadKeys
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.kernel.runtime.downloadChapterTask
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Limits and timings of [TaskExecutorService]. The defaults suit a home server; tests shrink them. */
data class ExecutorSettings(
    /** How many tasks run at the same time. */
    val maxConcurrentTasks: Int = 3,
    /** How many of those may use the same connector, so one source is not hit by every worker at once. */
    val maxTasksPerConnector: Int = 2,
    /** A task still running after this long is failed (and retried), so a hung source cannot stall the queue. */
    val taskTimeoutMs: Map<TaskType, Long> =
        mapOf(
            TaskType.FETCH_SERIES_METADATA to 2 * 60_000L,
            TaskType.FETCH_CHAPTERS to 2 * 60_000L,
            TaskType.DOWNLOAD_CHAPTER to 15 * 60_000L,
        ),
    /** The wait before the first, second, third retry. A task that failed after the last entry stays failed. */
    val retryDelaysMs: List<Long> = listOf(30_000L, 120_000L, 600_000L),
    /** Pages of one chapter are held in memory until the chapter is written; more than this fails the chapter. */
    val maxChapterBytes: Long = 512L * 1024 * 1024,
    /** A cover bigger than this is not downloaded; the remote URL is kept instead. */
    val maxCoverBytes: Int = 5 * 1024 * 1024,
)

// wired by the DI module; its collaborators are separate services, only the limits are grouped
@Suppress("LongParameterList")
class TaskExecutorService(
    private val taskQueue: TaskQueue,
    private val taskRepository: TaskRepository,
    private val connectorRegistry: ConnectorRegistry,
    private val seriesRepository: SeriesRepository,
    private val chapterRepository: ChapterRepository,
    private val fileStorage: FileStorage,
    private val httpClient: HttpClient,
    private val eventBus: EventBus,
    private val enricherRegistry: MetadataEnricherRegistry,
    private val settings: ExecutorSettings = ExecutorSettings(),
) {
    private val logger = KotlinLogging.logger {}
    private val lastHeartbeatMs = AtomicLong(0L)
    private val running = ConcurrentHashMap<Id, Deferred<Result<Unit>>>()
    private val cancelledByUser = ConcurrentHashMap.newKeySet<Id>()
    private val connectorPermits = ConcurrentHashMap<String, Semaphore>()

    /** True while [start] is running; independent of whether the workers are busy with a long task. */
    fun isAlive(): Boolean = System.currentTimeMillis() - lastHeartbeatMs.get() < HEARTBEAT_WINDOW_MS

    private companion object {
        const val HEARTBEAT_WINDOW_MS = 2_000L
        const val HEARTBEAT_INTERVAL_MS = 500L
        const val IDLE_POLL_MS = 500L
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    }

    /** Puts tasks that were queued or running when the server stopped back in the queue (retries keep their wait). */
    suspend fun recoverOnBoot() {
        val running = taskRepository.listAllByStatus(TaskStatus.RUNNING)
        val pending = taskRepository.listAllByStatus(TaskStatus.PENDING)
        for (task in running) {
            taskRepository.updateStatus(task.id, TaskStatus.PENDING)
        }
        for (task in pending + running) {
            taskQueue.enqueue(task)
        }
    }

    suspend fun start() {
        supervisorScope {
            eventBus.on<TaskEvents.TaskCancelled> { event ->
                val job = running[event.taskId] ?: return@on
                cancelledByUser += event.taskId
                job.cancel(CancellationException("Task cancelled by the user"))
            }
            launch {
                while (true) {
                    lastHeartbeatMs.set(System.currentTimeMillis())
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
            repeat(settings.maxConcurrentTasks) { launch { workerLoop() } }
        }
    }

    private suspend fun CoroutineScope.workerLoop() {
        while (true) {
            val task = taskQueue.dequeue()
            if (task == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            // a failure inside one task must never end the worker
            try {
                process(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Task ${task.id} [${task.type}] could not be processed" }
            }
        }
    }

    private suspend fun CoroutineScope.process(task: Task) {
        // a retry that is not due yet waits outside the worker, so it does not occupy a slot
        val waitMs = task.runAfter?.let { Duration.between(Instant.now(), it).toMillis() } ?: 0L
        if (waitMs > 0) {
            launch {
                delay(waitMs)
                taskQueue.enqueue(task)
            }
            return
        }
        // skip tasks cancelled between enqueue and dequeue
        if (taskQueue.getTask(task.id)?.status == TaskStatus.CANCELLED) {
            logger.info { "Task ${task.id} [${task.type}] skipped, cancelled while queued" }
            return
        }
        permitsFor(task).withPermit {
            taskRepository.updateStatus(task.id, TaskStatus.RUNNING)
            eventBus.publish(TaskEvents.TaskStarted(task.id, task.type, task.targetId, Instant.now()))
            logger.info { "Task ${task.id} [${task.type}] started, target=${task.targetId}" }
            val result = execute(task) ?: return@withPermit
            finish(task, result)
        }
    }

    /** Runs [task] with its timeout. Returns null when the user cancelled it. */
    private suspend fun CoroutineScope.execute(task: Task): Result<Unit>? {
        val timeoutMs = settings.taskTimeoutMs[task.type] ?: DEFAULT_TIMEOUT_MS
        val deferred =
            async {
                try {
                    withTimeout(timeoutMs) { dispatch(task) }
                } catch (_: TimeoutCancellationException) {
                    Result.Failure(
                        AppError.InternalError("Timed out after ${if (timeoutMs >= 1000) "${timeoutMs / 1000}s" else "${timeoutMs}ms"}"),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Task ${task.id} [${task.type}] threw uncaught exception" }
                    Result.Failure(AppError.InternalError(e.message ?: "Executor error"))
                }
            }
        running[task.id] = deferred
        // a cancel that came in before the job was registered found nothing to stop; the queue still remembers it
        if (taskQueue.getTask(task.id)?.status == TaskStatus.CANCELLED) {
            cancelledByUser += task.id
            deferred.cancel(CancellationException("Task cancelled by the user"))
        }
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            // cancelled by the user: the task ends quietly. Anything else is the server shutting down.
            if (task.id !in cancelledByUser) throw e
            logger.info { "Task ${task.id} [${task.type}] cancelled while running" }
            return null
        } finally {
            running.remove(task.id)
            cancelledByUser.remove(task.id)
        }
    }

    private suspend fun finish(
        task: Task,
        result: Result<Unit>,
    ) {
        // cancelled while it was finishing: keep CANCELLED instead of overwriting it with the outcome
        if (taskQueue.getTask(task.id)?.status == TaskStatus.CANCELLED) {
            taskQueue.forget(task.id)
            return
        }
        when (result) {
            is Result.Success -> {
                taskRepository.updateStatus(task.id, TaskStatus.COMPLETED)
                eventBus.publish(TaskEvents.TaskCompleted(task.id, task.type, task.targetId, Instant.now()))
                logger.info { "Task ${task.id} [${task.type}] completed" }
            }
            is Result.Failure -> {
                taskRepository.updateStatus(task.id, TaskStatus.FAILED, result.error.message)
                eventBus.publish(TaskEvents.TaskFailed(task.id, task.type, task.targetId, result.error.message, Instant.now()))
                logger.error { "Task ${task.id} [${task.type}] failed: ${result.error.message}" }
                // a missing or invalid target does not get better by waiting
                if (result.error !is AppError.NotFound && result.error !is AppError.ValidationError) scheduleRetry(task)
            }
        }
        taskQueue.forget(task.id)
    }

    /** The retry is saved as a pending task with its due time, so a restart still runs it at the right moment. */
    private suspend fun scheduleRetry(task: Task) {
        if (task.retryCount >= settings.retryDelaysMs.size) return
        val delayMs = settings.retryDelaysMs[task.retryCount]
        val now = Instant.now()
        val retryTask =
            task.copy(
                id = Id.generate(),
                status = TaskStatus.PENDING,
                retryCount = task.retryCount + 1,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                runAfter = now.plusMillis(delayMs),
            )
        logger.info { "Task ${task.id} will be retried (attempt ${retryTask.retryCount}/${settings.retryDelaysMs.size}) in ${delayMs}ms" }
        taskQueue.enqueue(retryTask)
    }

    private fun permitsFor(task: Task): Semaphore {
        val connectorId = task.payload[TaskPayloadKeys.CONNECTOR_ID].orEmpty()
        return connectorPermits.getOrPut(connectorId) { Semaphore(settings.maxTasksPerConnector) }
    }

    private suspend fun dispatch(task: Task): Result<Unit> =
        when (task.type) {
            TaskType.FETCH_SERIES_METADATA -> handleFetchSeriesMetadata(task)
            TaskType.FETCH_CHAPTERS -> handleFetchChapters(task)
            TaskType.DOWNLOAD_CHAPTER -> handleDownloadChapter(task)
        }

    internal suspend fun handleFetchSeriesMetadata(task: Task): Result<Unit> {
        val connectorId = task.payload[TaskPayloadKeys.CONNECTOR_ID] ?: ""
        val externalId = task.payload[TaskPayloadKeys.EXTERNAL_ID] ?: ""
        val language = task.payload[TaskPayloadKeys.LANGUAGE] ?: ""

        val connector =
            connectorRegistry.findById(connectorId)
                ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val metadata =
            when (val r = connector.fetchSeries(externalId)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }

        val localCoverUrl =
            metadata.coverUrl?.let { coverUrl ->
                try {
                    val bytes = httpClient.get(coverUrl).readBytesBounded(settings.maxCoverBytes)
                    fileStorage.writeCover(task.targetId.toString(), bytes)
                    ApiPaths.seriesCover(task.targetId.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn { "Cover download failed for series ${task.targetId}, using remote URL: ${e.message}" }
                    coverUrl
                }
            }

        when (val r = seriesRepository.updateMetadata(task.targetId, metadata.title, localCoverUrl, metadata.description)) {
            is Result.Success -> Unit
            is Result.Failure -> return r
        }

        // invoke enrichers in priority order; failures are logged, not fatal
        val enricherInput =
            EnricherInput(
                externalId = externalId,
                connectorId = connectorId,
                title = metadata.title,
                coverUrl = metadata.coverUrl,
                description = metadata.description,
            )
        // Enrichers run in priority order (lowest number first). Each enricher fully
        // replaces all enriched fields — last writer wins. Non-null fields from a
        // lower-priority enricher will be overwritten by a higher-priority one.
        enricherRegistry.all().forEach { enricher ->
            when (val r = enricher.enrich(enricherInput)) {
                is Result.Success -> {
                    val enriched = r.value
                    when (
                        val dbResult =
                            seriesRepository.updateEnrichedFields(
                                id = task.targetId,
                                author = enriched.author,
                                artist = enriched.artist,
                                year = enriched.year,
                                upstreamStatus = enriched.upstreamStatus,
                                genres = enriched.genres,
                            )
                    ) {
                        is Result.Success -> Unit
                        is Result.Failure ->
                            logger.warn {
                                "Failed to persist enriched fields for series ${task.targetId}: ${dbResult.error}"
                            }
                    }
                }
                is Result.Failure -> logger.warn { "Enricher '${enricher.id}' failed for series ${task.targetId}: ${r.error}" }
            }
        }

        taskQueue.enqueue(
            Task(
                id = Id.generate(),
                type = TaskType.FETCH_CHAPTERS,
                status = TaskStatus.PENDING,
                targetType = TargetType.SERIES,
                targetId = task.targetId,
                payload =
                    mapOf(
                        TaskPayloadKeys.CONNECTOR_ID to connectorId,
                        TaskPayloadKeys.EXTERNAL_ID to externalId,
                        TaskPayloadKeys.LANGUAGE to language,
                    ),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        return Result.Success(Unit)
    }

    internal suspend fun handleFetchChapters(task: Task): Result<Unit> {
        val connectorId = task.payload[TaskPayloadKeys.CONNECTOR_ID] ?: ""
        val externalId = task.payload[TaskPayloadKeys.EXTERNAL_ID] ?: ""
        val language = task.payload[TaskPayloadKeys.LANGUAGE].orEmpty().ifBlank { "en" }

        val connector =
            connectorRegistry.findById(connectorId)
                ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapters =
            when (val r = connector.fetchChapters(externalId, language)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }

        val insertedChapters = mutableListOf<Chapter>()
        for (ch in chapters) {
            val result = chapterRepository.insertChapter(task.targetId, ch.title, ch.chapterIndex, ch.externalId)
            if (result is Result.Success) {
                insertedChapters.add(result.value)
            }
        }

        val series =
            when (val r = seriesRepository.getSeries(task.targetId)) {
                is Result.Success -> r.value
                is Result.Failure -> return Result.Success(Unit)
            }

        if (insertedChapters.isNotEmpty()) {
            seriesRepository.stampUpdatedAt(task.targetId)
            eventBus.publish(NewChaptersDiscovered(series = series, chapters = insertedChapters))
        }

        if (series.autoDownload) {
            for (chapter in insertedChapters) {
                setChapterStatus(chapter, DownloadStatus.PENDING)
                taskQueue.enqueue(downloadChapterTask(chapter.id, connectorId, series.defaultFormat?.toString() ?: "Cbz"))
            }
        }

        return Result.Success(Unit)
    }

    internal suspend fun handleDownloadChapter(task: Task): Result<Unit> {
        val connectorId = task.payload[TaskPayloadKeys.CONNECTOR_ID] ?: ""

        // `chapter` not yet in scope, can't mark FAILED if connector missing
        val connector =
            connectorRegistry.findById(connectorId)
                ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapterId = Id.from(task.payload[TaskPayloadKeys.CHAPTER_ID] ?: task.targetId.toString())
        val chapter =
            when (val r = chapterRepository.getChapter(chapterId)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }

        setChapterStatus(chapter, DownloadStatus.DOWNLOADING)

        try {
            val format = ChapterFormat.fromString(task.payload[TaskPayloadKeys.FORMAT] ?: "Cbz")
            val downloadResult =
                when (val r = connector.download(chapter, format)) {
                    is Result.Success -> r.value
                    is Result.Failure -> {
                        setChapterStatus(chapter, DownloadStatus.FAILED)
                        return r
                    }
                }

            if (downloadResult.pages.isEmpty()) {
                setChapterStatus(chapter, DownloadStatus.FAILED)
                return Result.Failure(
                    AppError.InternalError(
                        "Connector $connectorId returned 0 pages for chapter ${chapter.id}",
                    ),
                )
            }

            val sortedPages = downloadResult.pages.sortedBy { it.index }
            val pages = mutableListOf<Page>()
            val failedIndices = mutableListOf<Int>()
            var totalBytes = 0L
            for (page in sortedPages) {
                when (val r = connector.fetchPage(page)) {
                    is Result.Success -> {
                        totalBytes += r.value.size
                        if (totalBytes > settings.maxChapterBytes) {
                            setChapterStatus(chapter, DownloadStatus.FAILED)
                            return Result.Failure(
                                AppError.InternalError(
                                    "Chapter ${chapter.id} is larger than the allowed ${settings.maxChapterBytes} bytes",
                                ),
                            )
                        }
                        pages.add(Page(page.index, r.value))
                    }
                    is Result.Failure -> failedIndices.add(page.index)
                }
            }
            if (failedIndices.isNotEmpty()) {
                setChapterStatus(chapter, DownloadStatus.FAILED)
                return Result.Failure(
                    AppError.InternalError(
                        "Failed to download ${failedIndices.size}/${downloadResult.pages.size} page(s) " +
                            "for chapter ${chapter.id}: indices $failedIndices",
                    ),
                )
            }

            when (val r = fileStorage.writeChapter(chapter.seriesId.toString(), chapter.id.toString(), pages, format)) {
                is Result.Failure -> {
                    setChapterStatus(chapter, DownloadStatus.FAILED)
                    return r
                }
                is Result.Success -> Unit
            }

            setChapterStatus(chapter, DownloadStatus.DOWNLOADED, pages.size, format)
            return Result.Success(Unit)
        } catch (e: Throwable) {
            // also when the task is being cancelled: the chapter must not stay "downloading"
            withContext(NonCancellable) { setChapterStatus(chapter, DownloadStatus.FAILED) }
            throw e
        }
    }

    private suspend fun setChapterStatus(
        chapter: Chapter,
        status: DownloadStatus,
        pageCount: Int? = null,
        format: ChapterFormat? = null,
    ) {
        chapterRepository.updateDownloadStatus(chapter.id, status, pageCount, format)
        eventBus.publish(ChapterEvents.DownloadStatusChanged(chapter.id, chapter.seriesId, status, Instant.now()))
    }
}
