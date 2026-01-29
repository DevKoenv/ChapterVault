package dev.koenv.chaptervault.opds.builder

import dev.koenv.chaptervault.opds.model.*
import java.time.Instant

/**
 * DSL builder for constructing OPDS feeds
 *
 * Example usage:
 * ```kotlin
 * val feed = opdsFeed {
 *     id = "http://example.com/opds"
 *     title = "My Library"
 *     updated = Instant.now()
 *     author { name = "ChapterVault" }
 *
 *     link {
 *         href = "/opds"
 *         rel = LinkRel.SELF
 *         type = MimeTypes.OPDS_NAVIGATION
 *     }
 *
 *     entry {
 *         id = "series-123"
 *         title = "My Series"
 *         link {
 *             href = "/opds/series/123"
 *             rel = LinkRel.SUBSECTION
 *         }
 *     }
 * }
 * ```
 */
@DslMarker
annotation class OpdsDsl

/**
 * Build an OPDS feed using the DSL
 */
fun opdsFeed(block: FeedBuilder.() -> Unit): OpdsFeed {
    return FeedBuilder().apply(block).build()
}

/**
 * Builder for OPDS feeds
 */
@OpdsDsl
class FeedBuilder {
    var id: String = ""
    var title: String = ""
    var updated: Instant = Instant.now()
    var subtitle: String? = null
    var icon: String? = null

    private var author: OpdsAuthor? = null
    private val links = mutableListOf<OpdsLink>()
    private val entries = mutableListOf<OpdsEntry>()

    fun author(block: AuthorBuilder.() -> Unit) {
        author = AuthorBuilder().apply(block).build()
    }

    fun link(block: LinkBuilder.() -> Unit) {
        links.add(LinkBuilder().apply(block).build())
    }

    fun entry(block: EntryBuilder.() -> Unit) {
        entries.add(EntryBuilder().apply(block).build())
    }

    fun build(): OpdsFeed = OpdsFeed(
        id = id,
        title = title,
        updated = updated,
        author = author,
        subtitle = subtitle,
        icon = icon,
        links = links.toList(),
        entries = entries.toList()
    )
}

/**
 * Builder for OPDS entries
 */
@OpdsDsl
class EntryBuilder {
    var id: String = ""
    var title: String = ""
    var updated: Instant = Instant.now()
    var summary: String? = null
    var published: Instant? = null

    private val authors = mutableListOf<OpdsAuthor>()
    private var content: OpdsContent? = null
    private val links = mutableListOf<OpdsLink>()
    private val categories = mutableListOf<OpdsCategory>()
    private var pse: PseExtension? = null

    fun author(block: AuthorBuilder.() -> Unit) {
        authors.add(AuthorBuilder().apply(block).build())
    }

    fun content(block: ContentBuilder.() -> Unit) {
        content = ContentBuilder().apply(block).build()
    }

    fun content(text: String) {
        content = OpdsContent(value = text, type = "text")
    }

    fun link(block: LinkBuilder.() -> Unit) {
        links.add(LinkBuilder().apply(block).build())
    }

    fun category(block: CategoryBuilder.() -> Unit) {
        categories.add(CategoryBuilder().apply(block).build())
    }

    fun category(term: String, label: String? = null) {
        categories.add(OpdsCategory(term = term, label = label))
    }

    /**
     * Add PSE (Page Streaming Extension) support
     */
    fun pse(pageCount: Int) {
        pse = PseExtension(count = pageCount)
    }

    fun build(): OpdsEntry = OpdsEntry(
        id = id,
        title = title,
        updated = updated,
        authors = authors.toList(),
        summary = summary,
        content = content,
        links = links.toList(),
        categories = categories.toList(),
        published = published,
        pse = pse
    )
}

/**
 * Builder for OPDS links
 */
@OpdsDsl
class LinkBuilder {
    var href: String = ""
    var rel: String = LinkRel.SELF
    var type: String? = null
    var title: String? = null
    var length: Long? = null

    private var pse: PseLinkExtension? = null

    /**
     * Add PSE link extension for page streaming
     */
    fun pse(count: Int, lastRead: Int? = null, lastReadDate: Instant? = null) {
        pse = PseLinkExtension(count = count, lastRead = lastRead, lastReadDate = lastReadDate)
    }

    fun build(): OpdsLink = OpdsLink(
        href = href,
        rel = rel,
        type = type,
        title = title,
        length = length,
        pse = pse
    )
}

/**
 * Builder for OPDS authors
 */
@OpdsDsl
class AuthorBuilder {
    var name: String = ""
    var uri: String? = null
    var email: String? = null

    fun build(): OpdsAuthor = OpdsAuthor(
        name = name,
        uri = uri,
        email = email
    )
}

/**
 * Builder for OPDS content
 */
@OpdsDsl
class ContentBuilder {
    var value: String = ""
    var type: String = "text"

    fun build(): OpdsContent = OpdsContent(
        value = value,
        type = type
    )
}

/**
 * Builder for OPDS categories
 */
@OpdsDsl
class CategoryBuilder {
    var term: String = ""
    var label: String? = null
    var scheme: String? = null

    fun build(): OpdsCategory = OpdsCategory(
        term = term,
        label = label,
        scheme = scheme
    )
}
