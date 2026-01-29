package dev.koenv.chaptervault.api.models

import kotlinx.serialization.Serializable

/**
 * Shared pagination DTO for list responses.
 */
@Serializable
data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Long,
    val hasMore: Boolean
)
