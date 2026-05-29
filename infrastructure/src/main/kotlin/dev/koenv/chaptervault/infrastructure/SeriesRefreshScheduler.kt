package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Instant

class SeriesRefreshScheduler(
    private val libraryRead: LibraryReadApi,
    private val taskQueue: TaskQueue,
    private val intervalHours: Int,
) {
    private val log = LoggerFactory.getLogger(SeriesRefreshScheduler::class.java)

    suspend fun start() {
        if (intervalHours <= 0) {
            log.info("Series auto-refresh disabled (intervalHours=$intervalHours)")
            return
        }
        val intervalMs = intervalHours * 3_600_000L
        log.info("Series auto-refresh enabled, interval=${intervalHours}h")
        while (true) {
            delay(intervalMs)
            enqueueRefreshTasks()
        }
    }

    private suspend fun enqueueRefreshTasks() {
        var page = 0
        var enqueued = 0
        while (true) {
            val result = libraryRead.listSeries(PageRequest(page, 100))
            if (result !is Result.Success) {
                log.error("Auto-refresh: failed to list series on page $page")
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
                                "connectorId" to series.connectorId,
                                "externalId" to series.externalId,
                                "language" to series.language,
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
        log.info("Auto-refresh: enqueued $enqueued FETCH_SERIES_METADATA tasks")
    }
}
