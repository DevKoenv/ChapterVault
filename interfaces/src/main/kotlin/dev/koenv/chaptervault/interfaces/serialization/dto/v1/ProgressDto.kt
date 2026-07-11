package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class ProgressDto(
    val seriesId: String,
    val latestChapterId: String?,
    val latestPage: Int,
    val unreadCount: Int,
    val totalCount: Int,
)
