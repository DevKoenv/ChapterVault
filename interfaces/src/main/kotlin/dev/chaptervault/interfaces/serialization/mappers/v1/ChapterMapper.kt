package dev.chaptervault.interfaces.serialization.mappers.v1

import dev.chaptervault.interfaces.serialization.dto.v1.ChapterDto
import dev.chaptervault.kernel.library.Chapter

fun Chapter.toDto(): ChapterDto = ChapterDto(
    id = id.toString(),
    seriesId = seriesId.toString(),
    title = title,
    chapterIndex = chapterIndex,
    status = status.name,
    format = format?.toString(),
    pageCount = pageCount,
)
