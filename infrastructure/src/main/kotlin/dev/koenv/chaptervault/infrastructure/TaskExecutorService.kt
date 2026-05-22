package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.HttpConnector
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.ChapterEvents
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskEvents
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Instant

class TaskExecutorService(
    private val taskQueue: TaskQueue,
    private val taskRepository: TaskRepository,
    private val connectorRegistry: ConnectorRegistry,
    private val seriesRepository: SeriesRepository,
    private val chapterRepository: ChapterRepository,
    private val fileStorage: FileStorage,
    private val httpClient: HttpClient,
    private val eventBus: EventBus,
) {
    private val log = LoggerFactory.getLogger(TaskExecutorService::class.java)

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
        while (true) {
            val task = taskQueue.dequeue()
            if (task == null) { delay(500); continue }
            taskRepository.insert(task)
            taskRepository.updateStatus(task.id, TaskStatus.RUNNING)
            eventBus.publish(TaskEvents.TaskStarted(task.id, task.type, task.targetId, Instant.now()))
            log.info("Task ${task.id} [${task.type}] started — target=${task.targetId}")
            val result = runCatching { dispatch(task) }.getOrElse { e ->
                log.error("Task ${task.id} [${task.type}] threw uncaught exception", e)
                Result.Failure(AppError.InternalError(e.message ?: "Executor error"))
            }
            when (result) {
                is Result.Success -> {
                    taskRepository.updateStatus(task.id, TaskStatus.COMPLETED)
                    eventBus.publish(TaskEvents.TaskCompleted(task.id, task.type, task.targetId, Instant.now()))
                    log.info("Task ${task.id} [${task.type}] completed")
                }
                is Result.Failure -> {
                    taskRepository.updateStatus(task.id, TaskStatus.FAILED, result.error.message)
                    eventBus.publish(TaskEvents.TaskFailed(task.id, task.type, task.targetId, result.error.message, Instant.now()))
                    log.error("Task ${task.id} [${task.type}] failed: ${result.error.message}")
                }
            }
        }
    }

    private suspend fun dispatch(task: Task): Result<Unit> = when (task.type) {
        TaskType.FETCH_SERIES_METADATA -> handleFetchSeriesMetadata(task)
        TaskType.FETCH_CHAPTERS -> handleFetchChapters(task)
        TaskType.DOWNLOAD_CHAPTER -> handleDownloadChapter(task)
        TaskType.DOWNLOAD_SERIES -> Result.Failure(AppError.InternalError("DOWNLOAD_SERIES not handled by executor"))
    }

    private suspend fun handleFetchSeriesMetadata(task: Task): Result<Unit> {
        val connectorId = task.payload["connectorId"] ?: ""
        val externalId = task.payload["externalId"] ?: ""
        val language = task.payload["language"] ?: ""

        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val metadata = when (val r = connector.fetchSeries(externalId)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        val localCoverUrl = metadata.coverUrl?.let { coverUrl ->
            try {
                val bytes = httpClient.get(coverUrl).readRawBytes()
                fileStorage.writeCover(task.targetId.toString(), bytes)
                "/library/series/${task.targetId}/cover"
            } catch (e: Exception) {
                log.warn("Cover download failed for series ${task.targetId}, using remote URL: ${e.message}")
                coverUrl
            }
        }

        when (val r = seriesRepository.updateMetadata(task.targetId, metadata.title, localCoverUrl, metadata.description)) {
            is Result.Success -> Unit
            is Result.Failure -> return r
        }

        taskQueue.enqueue(
            Task(
                id = Id.generate(),
                type = TaskType.FETCH_CHAPTERS,
                status = TaskStatus.PENDING,
                targetType = TargetType.SERIES,
                targetId = task.targetId,
                payload = mapOf(
                    "connectorId" to connectorId,
                    "externalId" to externalId,
                    "language" to language,
                ),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )

        return Result.Success(Unit)
    }

    private suspend fun handleFetchChapters(task: Task): Result<Unit> {
        val connectorId = task.payload["connectorId"] ?: ""
        val externalId = task.payload["externalId"] ?: ""
        val language = task.payload["language"] ?: ""

        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapters = when (val r = connector.fetchChapters(externalId, language)) {
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

        val series = when (val r = seriesRepository.getSeries(task.targetId)) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.Success(Unit)
        }

        if (series.autoDownload) {
            for (chapter in insertedChapters) {
                setChapterStatus(chapter, DownloadStatus.PENDING)
                taskQueue.enqueue(
                    Task(
                        id = Id.generate(),
                        type = TaskType.DOWNLOAD_CHAPTER,
                        status = TaskStatus.PENDING,
                        targetType = TargetType.CHAPTER,
                        targetId = chapter.id,
                        payload = mapOf(
                            "connectorId" to connectorId,
                            "chapterId" to chapter.id.toString(),
                            "format" to (series.defaultFormat?.toString() ?: "Cbz"),
                        ),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                )
            }
        }

        return Result.Success(Unit)
    }

    internal suspend fun handleDownloadChapter(task: Task): Result<Unit> {
        val connectorId = task.payload["connectorId"] ?: ""

        // Chapter has not been fetched yet at this point, so we cannot update its download status to FAILED here.
        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapterId = Id.from(task.payload["chapterId"] ?: task.targetId.toString())
        val chapter = when (val r = chapterRepository.getChapter(chapterId)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        setChapterStatus(chapter, DownloadStatus.DOWNLOADING)

        try {
            val format = ChapterFormat.fromString(task.payload["format"] ?: "Cbz")
            val downloadResult = when (val r = connector.download(chapter, format)) {
                is Result.Success -> r.value
                is Result.Failure -> {
                    setChapterStatus(chapter, DownloadStatus.FAILED)
                    return r
                }
            }

            if (downloadResult.pages.isEmpty()) {
                setChapterStatus(chapter, DownloadStatus.FAILED)
                return Result.Failure(AppError.InternalError(
                    "Connector $connectorId returned 0 pages for chapter ${chapter.id}"
                ))
            }

            val httpConnector = connector as? HttpConnector
                ?: run {
                    setChapterStatus(chapter, DownloadStatus.FAILED)
                    return Result.Failure(AppError.InternalError(
                        "Connector $connectorId does not support HTTP page fetching"
                    ))
                }

            val sortedPages = downloadResult.pages.sortedBy { it.index }
            val pages = mutableListOf<Page>()
            val failedIndices = mutableListOf<Int>()
            for (page in sortedPages) {
                when (val r = httpConnector.fetchPage(page)) {
                    is Result.Success -> pages.add(Page(page.index, r.value))
                    is Result.Failure -> failedIndices.add(page.index)
                }
            }
            if (failedIndices.isNotEmpty()) {
                setChapterStatus(chapter, DownloadStatus.FAILED)
                return Result.Failure(AppError.InternalError(
                    "Failed to download ${failedIndices.size}/${downloadResult.pages.size} page(s) " +
                        "for chapter ${chapter.id}: indices $failedIndices"
                ))
            }

            when (val r = fileStorage.writeChapter(chapter.seriesId.toString(), chapter.id.toString(), pages, format)) {
                is Result.Failure -> {
                    setChapterStatus(chapter, DownloadStatus.FAILED)
                    return r
                }
                is Result.Success -> Unit
            }

            setChapterStatus(chapter, DownloadStatus.DOWNLOADED)
            return Result.Success(Unit)
        } catch (e: Throwable) {
            setChapterStatus(chapter, DownloadStatus.FAILED)
            throw e
        }
    }

    private suspend fun setChapterStatus(chapter: Chapter, status: DownloadStatus) {
        chapterRepository.updateDownloadStatus(chapter.id, status)
        eventBus.publish(ChapterEvents.DownloadStatusChanged(chapter.id, chapter.seriesId, status, Instant.now()))
    }
}
