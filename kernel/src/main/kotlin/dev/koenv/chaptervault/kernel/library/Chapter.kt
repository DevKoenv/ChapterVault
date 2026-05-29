package dev.koenv.chaptervault.kernel.library

import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class Chapter(
    val id: Id,
    val seriesId: Id,
    val title: String,
    val chapterIndex: Double,
    val externalId: String,
    val downloadStatus: DownloadStatus,
    val format: ChapterFormat? = null,
    val pageCount: Int? = null,
    val addedAt: Instant,
    val updatedAt: Instant,
)
