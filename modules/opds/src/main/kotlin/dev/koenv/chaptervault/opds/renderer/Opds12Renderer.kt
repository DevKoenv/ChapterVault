package dev.koenv.chaptervault.opds.renderer

import dev.koenv.chaptervault.opds.model.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * OPDS 1.2 Renderer - Outputs Atom XML format
 *
 * Follows the OPDS 1.2 specification for Atom-based feeds.
 * Includes PSE (Page Streaming Extension) support.
 */
class Opds12Renderer : FeedRenderer {
    override val version = OpdsVersion.V1_2
    override val contentType = "application/atom+xml;charset=utf-8"

    companion object {
        private const val ATOM_NS = "http://www.w3.org/2005/Atom"
        private const val DC_NS = "http://purl.org/dc/terms/"
        private const val OPDS_NS = "http://opds-spec.org/2010/catalog"
        private const val PSE_NS = "http://vaemendis.net/opds-pse/ns"

        private val ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT
    }

    override fun render(feed: OpdsFeed): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(buildFeedElement(feed))
        }
    }

    private fun buildFeedElement(feed: OpdsFeed): String {
        return buildString {
            append("<feed")
            append(""" xmlns="$ATOM_NS"""")
            append(""" xmlns:dc="$DC_NS"""")
            append(""" xmlns:opds="$OPDS_NS"""")
            // Include PSE namespace if any entries have PSE
            if (feed.entries.any { it.pse != null || it.links.any { l -> l.pse != null } }) {
                append(""" xmlns:pse="$PSE_NS"""")
            }
            appendLine(">")

            // Required elements
            appendLine("  <id>${escapeXml(feed.id)}</id>")
            appendLine("  <title>${escapeXml(feed.title)}</title>")
            appendLine("  <updated>${formatInstant(feed.updated)}</updated>")

            // Optional elements
            feed.subtitle?.let {
                appendLine("  <subtitle>${escapeXml(it)}</subtitle>")
            }

            feed.icon?.let {
                appendLine("  <icon>${escapeXml(it)}</icon>")
            }

            // Author
            feed.author?.let { author ->
                appendLine(renderAuthor(author, "  "))
            }

            // Links
            feed.links.forEach { link ->
                appendLine(renderLink(link, "  "))
            }

            // Entries
            feed.entries.forEach { entry ->
                appendLine(renderEntry(entry, "  "))
            }

            append("</feed>")
        }
    }

    private fun renderEntry(entry: OpdsEntry, indent: String): String {
        return buildString {
            appendLine("$indent<entry>")

            // Required elements
            appendLine("$indent  <id>${escapeXml(entry.id)}</id>")
            appendLine("$indent  <title>${escapeXml(entry.title)}</title>")
            appendLine("$indent  <updated>${formatInstant(entry.updated)}</updated>")

            // Optional elements
            entry.published?.let {
                appendLine("$indent  <published>${formatInstant(it)}</published>")
            }

            // Authors
            entry.authors.forEach { author ->
                appendLine(renderAuthor(author, "$indent  "))
            }

            // Summary
            entry.summary?.let {
                appendLine("$indent  <summary type=\"text\">${escapeXml(it)}</summary>")
            }

            // Content
            entry.content?.let { content ->
                when (content.type) {
                    "html" -> appendLine("$indent  <content type=\"html\">${escapeXml(content.value)}</content>")
                    "xhtml" -> appendLine("$indent  <content type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\">${content.value}</div></content>")
                    else -> appendLine("$indent  <content type=\"text\">${escapeXml(content.value)}</content>")
                }
            }

            // Categories
            entry.categories.forEach { category ->
                append("$indent  <category term=\"${escapeXml(category.term)}\"")
                category.label?.let { append(" label=\"${escapeXml(it)}\"") }
                category.scheme?.let { append(" scheme=\"${escapeXml(it)}\"") }
                appendLine("/>")
            }

            // Links
            entry.links.forEach { link ->
                appendLine(renderLink(link, "$indent  "))
            }

            // PSE extension at entry level
            entry.pse?.let { pse ->
                appendLine("$indent  <pse:count>${pse.count}</pse:count>")
            }

            append("$indent</entry>")
        }
    }

    private fun renderLink(link: OpdsLink, indent: String): String {
        return buildString {
            append("$indent<link")
            append(" rel=\"${escapeXml(link.rel)}\"")
            append(" href=\"${escapeXml(link.href)}\"")
            link.type?.let { append(" type=\"${escapeXml(it)}\"") }
            link.title?.let { append(" title=\"${escapeXml(it)}\"") }
            link.length?.let { append(" length=\"$it\"") }

            // PSE extension attributes
            link.pse?.let { pse ->
                append(" pse:count=\"${pse.count}\"")
                pse.lastRead?.let { append(" pse:lastRead=\"$it\"") }
                pse.lastReadDate?.let { append(" pse:lastReadDate=\"${formatInstant(it)}\"") }
            }

            append("/>")
        }
    }

    private fun renderAuthor(author: OpdsAuthor, indent: String): String {
        return buildString {
            appendLine("$indent<author>")
            appendLine("$indent  <name>${escapeXml(author.name)}</name>")
            author.uri?.let { appendLine("$indent  <uri>${escapeXml(it)}</uri>") }
            author.email?.let { appendLine("$indent  <email>${escapeXml(it)}</email>") }
            append("$indent</author>")
        }
    }

    private fun formatInstant(instant: java.time.Instant): String {
        return instant.atOffset(ZoneOffset.UTC).format(ISO_FORMATTER)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
