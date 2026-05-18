package dev.koenv.chaptervault.interfaces.serialization.mappers.v1

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ProgressDto
import dev.koenv.chaptervault.kernel.api.ReadProgress

fun ReadProgress.toDto() = ProgressDto(
    seriesId = seriesId.toString(),
    readCount = readCount,
    totalCount = totalCount,
)
