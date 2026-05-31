package dev.koenv.chaptervault.infrastructure.connectors.mangadex.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class MangaDexSearchResponse(
    val result: String = "",
    val data: List<MangaDexMangaData> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
internal data class MangaDexMangaResponse(
    val result: String = "",
    val data: MangaDexMangaData? = null,
)

@Serializable
internal data class MangaDexMangaData(
    val id: String = "",
    val attributes: MangaDexMangaAttributes = MangaDexMangaAttributes(),
    val relationships: List<MangaDexRelationship> = emptyList(),
)

@Serializable
internal data class MangaDexMangaAttributes(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val status: String? = null,
    val contentRating: String? = null,
)

@Serializable
internal data class MangaDexRelationship(
    val id: String = "",
    val type: String = "",
    val attributes: JsonElement? = null,
)

@Serializable
internal data class MangaDexCoverArtAttributes(
    val fileName: String = "",
)

@Serializable
internal data class MangaDexAuthorAttributes(
    val name: String = "",
)
