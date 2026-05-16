package dev.chaptervault.extensions.opds

data class OpdsFeed(
    val title: String,
    val id: String,
    val entries: List<OpdsEntry>,
    val totalResults: Long,
    val itemsPerPage: Int,
    val startIndex: Int,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val summary: String? = null,
    val coverUrl: String? = null,
    val links: List<OpdsLink> = emptyList(),
)

data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String,
)
