package dev.koenv.chaptervault.extensions.connectors.sources.mangadex.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class MangaDexAtHomeResponse(
    val result: String = "",
    val baseUrl: String = "",
    val chapter: MangaDexAtHomeChapter = MangaDexAtHomeChapter(),
)

@Serializable
internal data class MangaDexAtHomeChapter(
    val hash: String = "",
    val data: List<String> = emptyList(),
)
