package dev.koenv.chaptervault.kernel.library

import dev.koenv.chaptervault.shared.utils.Id
import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.time.Instant

data class Series(
    val id: Id,
    val title: String,
    val connectorId: String,
    val externalId: String,
    val status: SeriesStatus,
    val autoDownload: Boolean = false,
    val defaultFormat: ChapterFormat? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val addedAt: Instant,
    val updatedAt: Instant,
)
