package dev.koenv.chaptervault.kernel.library

import dev.koenv.chaptervault.kernel.event.DomainEvent
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

sealed class ChapterEvents : DomainEvent() {
    data class DownloadStatusChanged(
        val chapterId: Id,
        val seriesId: Id,
        val status: DownloadStatus,
        val occurredAt: Instant,
    ) : ChapterEvents()
}
