package dev.koenv.chaptervault.interfaces.serialization.dto.v1

import kotlinx.serialization.Serializable

@Serializable
data class CreateBookmarkRequest(val page: Int)
