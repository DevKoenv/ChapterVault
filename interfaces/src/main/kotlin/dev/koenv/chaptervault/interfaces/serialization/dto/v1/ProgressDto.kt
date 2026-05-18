package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class ProgressDto(
    val seriesId: String,
    val readCount: Int,
    val totalCount: Int,
)
