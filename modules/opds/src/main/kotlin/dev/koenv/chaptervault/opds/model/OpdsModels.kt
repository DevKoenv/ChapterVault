package dev.koenv.chaptervault.opds.model

import java.time.Instant
import java.util.UUID

/**
 * OPDS feed types
 */
enum class FeedKind {
    NAVIGATION,  // Navigation feed (browse catalogs)
    ACQUISITION  // Acquisition feed (download content)
}

/**
 * OPDS link relations
 */
object LinkRel {
    const val SELF = "self"
    const val START = "start"
    const val UP = "up"
    const val SUBSECTION = "subsection"
    const val SEARCH = "search"
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val ACQUISITION_OPEN = "http://opds-spec.org/acquisition/open-access"
    const val IMAGE = "http://opds-spec.org/image"
    const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val COVER = "http://opds-spec.org/cover"
    const val STREAM = "http://opds-spec.org/stream"  // PSE extension
    const val PAGE_STREAM = "http://vaemendis.net/opds-pse/stream"  // PSE page streaming
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val FIRST = "first"
    const val LAST = "last"
}

/**
 * Common MIME types for OPDS
 */
object MimeTypes {
    const val OPDS_CATALOG = "application/atom+xml;profile=opds-catalog"
    const val OPDS_NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
    const val OPDS_ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"
    const val ATOM_XML = "application/atom+xml"
    const val CBZ = "application/x-cbz"
    const val CBR = "application/x-cbr"
    const val EPUB = "application/epub+zip"
    const val PDF = "application/pdf"
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"

    // OPDS 2.0 types (for future)
    const val OPDS_JSON = "application/opds+json"
    const val OPDS_PUBLICATION = "application/opds-publication+json"
}

/**
 * OPDS Feed - represents a complete feed document
 */
data class OpdsFeed(
    val id: String,
    val title: String,
    val updated: Instant,
    val author: OpdsAuthor? = null,
    val subtitle: String? = null,
    val icon: String? = null,
    val links: List<OpdsLink> = emptyList(),
    val entries: List<OpdsEntry> = emptyList()
)

/**
 * OPDS Entry - represents a single item in a feed
 */
data class OpdsEntry(
    val id: String,
    val title: String,
    val updated: Instant,
    val authors: List<OpdsAuthor> = emptyList(),
    val summary: String? = null,
    val content: OpdsContent? = null,
    val links: List<OpdsLink> = emptyList(),
    val categories: List<OpdsCategory> = emptyList(),
    val published: Instant? = null,
    // PSE extension fields
    val pse: PseExtension? = null
)

/**
 * OPDS Link
 */
data class OpdsLink(
    val href: String,
    val rel: String,
    val type: String? = null,
    val title: String? = null,
    val length: Long? = null,
    // PSE extension
    val pse: PseLinkExtension? = null
)

/**
 * OPDS Author
 */
data class OpdsAuthor(
    val name: String,
    val uri: String? = null,
    val email: String? = null
)

/**
 * OPDS Content
 */
data class OpdsContent(
    val value: String,
    val type: String = "text"  // "text", "html", "xhtml"
)

/**
 * OPDS Category (for tags/genres)
 */
data class OpdsCategory(
    val term: String,
    val label: String? = null,
    val scheme: String? = null
)

/**
 * PSE (Page Streaming Extension) support for OPDS 1.2
 * Allows page-by-page streaming instead of downloading entire files
 */
data class PseExtension(
    val count: Int  // Total page count
)

/**
 * PSE link extension
 */
data class PseLinkExtension(
    val count: Int,           // Total pages
    val lastRead: Int? = null,   // Last read page
    val lastReadDate: Instant? = null
)

/**
 * OPDS version enumeration
 */
enum class OpdsVersion {
    V1_2,  // Atom-based (current stable)
    V2_0   // JSON-LD based (future)
}
