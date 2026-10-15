package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** Deletes finished task records older than [retentionDays] once a day, so the task table does not grow forever. */
class TaskRetention(
    private val taskRepository: TaskRepository,
    private val retentionDays: Int,
) {
    suspend fun start() {
        if (retentionDays <= 0) {
            logger.info { "Task records are kept forever (retentionDays=$retentionDays)" }
            return
        }
        while (true) {
            val removed = taskRepository.deleteFinishedBefore(Instant.now().minus(Duration.ofDays(retentionDays.toLong())))
            if (removed > 0) logger.info { "Removed $removed finished task records older than $retentionDays days" }
            delay(Duration.ofDays(1).toMillis())
        }
    }
}
