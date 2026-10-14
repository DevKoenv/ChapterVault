package dev.koenv.chaptervault.kernel.runtime

import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadChapterTaskTest {
    @Test
    fun `a download task targets the chapter and carries what the executor reads`() {
        val chapterId = Id.generate()
        val now = Instant.parse("2026-10-14T12:00:00Z")

        val task = downloadChapterTask(chapterId, connectorId = "mangadex", format = "Cbz", now = now)

        assertEquals(TaskType.DOWNLOAD_CHAPTER, task.type)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(TargetType.CHAPTER, task.targetType)
        assertEquals(chapterId, task.targetId)
        assertEquals(
            mapOf(
                TaskPayloadKeys.CONNECTOR_ID to "mangadex",
                TaskPayloadKeys.CHAPTER_ID to chapterId.toString(),
                TaskPayloadKeys.FORMAT to "Cbz",
            ),
            task.payload,
        )
        assertEquals(now, task.createdAt)
        assertEquals(now, task.updatedAt)
    }
}
