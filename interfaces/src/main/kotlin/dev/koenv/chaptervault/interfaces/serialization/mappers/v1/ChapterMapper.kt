package dev.koenv.chaptervault.interfaces.serialization.mappers.v1

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ChapterDto
import dev.koenv.chaptervault.kernel.library.Chapter

fun Chapter.toDto(): ChapterDto = ChapterDto(
    id = id.toString(),
    seriesId = seriesId.toString(),
    title = title,
    chapterIndex = chapterIndex,
    downloadStatus = downloadStatus.name,
    format = format?.toString(),
    pageCount = pageCount,
)
