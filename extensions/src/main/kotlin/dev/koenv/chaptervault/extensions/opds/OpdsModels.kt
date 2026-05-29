package dev.koenv.chaptervault.extensions.opds

data class OpdsFeed(
    val id: String,
    val title: String,
    val updated: String,
    val selfHref: String,
    val kind: String,
    val coverHref: String? = null,
    val nextHref: String? = null,
    val prevHref: String? = null,
    val totalResults: Long? = null,
    val itemsPerPage: Int? = null,
    val startIndex: Int? = null,
    val entries: List<OpdsEntry>,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val updated: String,
    val summary: String? = null,
    val content: String? = null,
    val links: List<OpdsLink> = emptyList(),
)

data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String,
    val pseCount: Int? = null,
)
