package dev.koenv.chaptervault.interfaces.serialization.mappers.v1

import dev.koenv.chaptervault.interfaces.serialization.dto.v1.SeriesDto
import dev.koenv.chaptervault.kernel.library.Series

fun Series.toDto(): SeriesDto = SeriesDto(
    id = id.toString(),
    title = title,
    connectorId = connectorId,
    externalId = externalId,
    language = language,
    status = status.name,
    autoDownload = autoDownload,
    coverUrl = coverUrl,
    description = description,
)
