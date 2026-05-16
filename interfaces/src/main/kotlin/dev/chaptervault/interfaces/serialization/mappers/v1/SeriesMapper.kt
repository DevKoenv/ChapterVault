package dev.chaptervault.interfaces.serialization.mappers.v1

import dev.chaptervault.interfaces.serialization.dto.v1.SeriesDto
import dev.chaptervault.kernel.library.Series

fun Series.toDto(): SeriesDto = SeriesDto(
    id = id.toString(),
    title = title,
    connectorId = connectorId,
    externalId = externalId,
    status = status.name,
    autoDownload = autoDownload,
    coverUrl = coverUrl,
    description = description,
)
