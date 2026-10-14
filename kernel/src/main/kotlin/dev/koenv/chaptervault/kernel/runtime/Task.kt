package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class Task(
    val id: Id,
    val type: TaskType,
    val status: TaskStatus,
    val targetType: TargetType,
    val targetId: Id,
    val payload: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    /** A task is not started before this moment (set on retries). Null means as soon as a worker is free. */
    val runAfter: Instant? = null,
)

/** The task that downloads one chapter; every place that queues a download builds it here. */
fun downloadChapterTask(
    chapterId: Id,
    connectorId: String,
    format: String,
    now: Instant = Instant.now(),
): Task =
    Task(
        id = Id.generate(),
        type = TaskType.DOWNLOAD_CHAPTER,
        status = TaskStatus.PENDING,
        targetType = TargetType.CHAPTER,
        targetId = chapterId,
        payload =
            mapOf(
                TaskPayloadKeys.CONNECTOR_ID to connectorId,
                TaskPayloadKeys.CHAPTER_ID to chapterId.toString(),
                TaskPayloadKeys.FORMAT to format,
            ),
        createdAt = now,
        updatedAt = now,
    )
