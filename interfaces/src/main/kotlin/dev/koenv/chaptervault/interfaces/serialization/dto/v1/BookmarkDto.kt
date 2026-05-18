package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class BookmarkDto(
    val id: String,
    val chapterId: String,
    val page: Int,
    val createdAt: String,
)
