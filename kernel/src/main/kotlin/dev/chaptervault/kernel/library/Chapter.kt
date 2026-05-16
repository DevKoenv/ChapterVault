package dev.chaptervault.kernel.library

import dev.chaptervault.shared.utils.Id
import dev.chaptervault.shared.format.ChapterFormat
import java.time.Instant

data class Chapter(
    val id: Id,
    val seriesId: Id,
    val title: String,
    val chapterIndex: Double,
    val externalId: String,
    val status: ChapterStatus,
    val format: ChapterFormat? = null,
    val pageCount: Int? = null,
    val addedAt: Instant,
    val updatedAt: Instant,
)
