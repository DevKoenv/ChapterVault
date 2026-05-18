package dev.koenv.chaptervault.interfaces.serialization.mappers.v1

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.BookmarkDto
import dev.koenv.chaptervault.kernel.api.Bookmark

fun Bookmark.toDto() = BookmarkDto(
    id = id.toString(),
    chapterId = chapterId.toString(),
    page = page,
    createdAt = createdAt.toString(),
)
