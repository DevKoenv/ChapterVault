package dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class MangaDexChapterListResponse(
    val result: String = "",
    val data: List<MangaDexChapterData> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
internal data class MangaDexChapterData(
    val id: String = "",
    val attributes: MangaDexChapterAttributes = MangaDexChapterAttributes(),
)

@Serializable
internal data class MangaDexChapterAttributes(
    val title: String? = null,
    val volume: String? = null,
    val chapter: String? = null,
    val pages: Int = 0,
    val externalUrl: String? = null,
    val publishAt: String? = null,
)
