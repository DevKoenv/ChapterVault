package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.infrastructure.storage.Page
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.delay
import java.time.Instant

class TaskExecutorService(
    private val taskQueue: TaskQueue,
    private val taskRepository: TaskRepository,
    private val connectorRegistry: ConnectorRegistry,
    private val seriesRepository: SeriesRepository,
    private val chapterRepository: ChapterRepository,
    private val fileStorage: FileStorage,
) {

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
            val result = runCatching { dispatch(task) }.getOrElse { e ->
                Result.Failure(AppError.InternalError(e.message ?: "Executor error"))
            }
            when (result) {
                is Result.Success -> taskRepository.updateStatus(task.id, TaskStatus.COMPLETED)
                is Result.Failure -> taskRepository.updateStatus(task.id, TaskStatus.FAILED, result.error.message)
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

        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val metadata = when (val r = connector.fetchSeries(externalId)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        when (val r = seriesRepository.updateMetadata(task.targetId, metadata.title, metadata.coverUrl, metadata.description)) {
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

        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapters = when (val r = connector.fetchChapters(externalId)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        val insertedChapters = mutableListOf<dev.koenv.chaptervault.kernel.library.Chapter>()
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

    private suspend fun handleDownloadChapter(task: Task): Result<Unit> {
        val connectorId = task.payload["connectorId"] ?: ""

        val connector = connectorRegistry.findById(connectorId)
            ?: return Result.Failure(AppError.InternalError("Connector not found: $connectorId"))

        val chapterId = Id.from(task.payload["chapterId"] ?: task.targetId.toString())
        val chapter = when (val r = chapterRepository.getChapter(chapterId)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        chapterRepository.updateDownloadStatus(chapter.id, DownloadStatus.DOWNLOADING)

        val format = ChapterFormat.fromString(task.payload["format"] ?: "Cbz")
        val downloadResult = when (val r = connector.download(chapter, format)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        val context = connectorRegistry.getContext(connectorId)
        val pages: List<Page> = if (context != null) {
            downloadResult.pageUrls.mapIndexedNotNull { index, url ->
                when (val r = context.download(url)) {
                    is Result.Success -> Page(index, r.value)
                    is Result.Failure -> null
                }
            }
        } else {
            emptyList()
        }

        if (context != null) {
            if (pages.isEmpty()) {
                return Result.Failure(AppError.InternalError("All page downloads failed for chapter ${chapter.id}"))
            }
            when (val r = fileStorage.writeChapter(chapter.seriesId.toString(), chapter.id.toString(), pages, format)) {
                is Result.Failure -> return r
                is Result.Success -> Unit
            }
        }

        chapterRepository.updateDownloadStatus(chapter.id, DownloadStatus.DOWNLOADED)

        return Result.Success(Unit)
    }
}
