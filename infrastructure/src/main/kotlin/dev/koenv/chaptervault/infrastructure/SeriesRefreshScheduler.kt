package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskPayloadKeys
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.time.Instant

private val logger = KotlinLogging.logger {}

class SeriesRefreshScheduler(
    private val libraryRead: LibraryReadApi,
    private val taskQueue: TaskQueue,
    private val intervalHours: Int,
) {
    suspend fun start() {
        if (intervalHours <= 0) {
            logger.info { "Series auto-refresh disabled (intervalHours=$intervalHours)" }
            return
        }
        val intervalMs = intervalHours * 3_600_000L
        logger.info { "Series auto-refresh enabled, interval=${intervalHours}h" }
        while (true) {
            delay(intervalMs)
            enqueueRefreshTasks()
        }
    }

    private suspend fun enqueueRefreshTasks() {
        var page = 0
        var enqueued = 0
        @Suppress("LoopWithTooManyJumpStatements")
        while (true) {
            val result = libraryRead.listSeries(PageRequest(page, 100))
            if (result !is Result.Success) {
                logger.error { "Auto-refresh: failed to list series on page $page" }
                break
            }
            val pagination = result.value
            for (series in pagination.items) {
                val now = Instant.now()
                taskQueue.enqueue(
                    Task(
                        id = Id.generate(),
                        type = TaskType.FETCH_SERIES_METADATA,
                        status = TaskStatus.PENDING,
                        targetType = TargetType.SERIES,
                        targetId = series.id,
                        payload =
                            mapOf(
                                TaskPayloadKeys.CONNECTOR_ID to series.connectorId,
                                TaskPayloadKeys.EXTERNAL_ID to series.externalId,
                                TaskPayloadKeys.LANGUAGE to series.language,
                            ),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                enqueued++
            }
            if (!pagination.hasNext) break
            page++
        }
        logger.info { "Auto-refresh: enqueued $enqueued FETCH_SERIES_METADATA tasks" }
    }
}
