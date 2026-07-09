package dev.koenv.chaptervault.interfaces.api.opds

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class OpdsXmlSerializerTest {
    private val serializer = OpdsV1()

    private fun minimalFeed(title: String = "ChapterVault", entries: List<OpdsEntry> = emptyList()) = OpdsFeed(
        id = "urn:chaptervault:root",
        title = title,
        updated = "2026-07-08T12:00:00Z",
        selfHref = "/opds/v1",
        kind = "navigation",
        entries = entries,
    )

    @Test
    fun `serializes XML declaration with UTF-8 charset`() {
        val xml = serializer.serialize(minimalFeed())
        assertContains(xml, "<?xml version=")
        assertContains(xml, "UTF-8")
    }

    @Test
    fun `includes Atom namespace on feed root`() {
        val xml = serializer.serialize(minimalFeed())
        assertContains(xml, "http://www.w3.org/2005/Atom")
    }

    @Test
    fun `serializes feed id and title as child elements`() {
        val xml = serializer.serialize(minimalFeed(title = "My Library"))
        assertContains(xml, "<id>urn:chaptervault:root</id>")
        assertContains(xml, "<title>My Library</title>")
    }

    @Test
    fun `serializes self link with rel and href attributes`() {
        val xml = serializer.serialize(minimalFeed())
        assertContains(xml, "rel=\"self\"")
        assertContains(xml, "href=\"/opds/v1\"")
    }

    @Test
    fun `serializes next and previous pagination links when present`() {
        val feed = minimalFeed().copy(
            nextHref = "/opds/v1/catalog?page=2",
            prevHref = "/opds/v1/catalog?page=0",
        )
        val xml = serializer.serialize(feed)
        assertContains(xml, "rel=\"next\"")
        assertContains(xml, "rel=\"previous\"")
        assertContains(xml, "/opds/v1/catalog?page=2")
    }

    @Test
    fun `serializes OpenSearch totalResults in os namespace`() {
        val feed = minimalFeed().copy(totalResults = 42L, itemsPerPage = 20, startIndex = 1)
        val xml = serializer.serialize(feed)
        assertContains(xml, "42")
        assertContains(xml, "http://a9.com/-/spec/opensearch/1.1/")
    }

    @Test
    fun `serializes entry with id, title, updated, summary`() {
        val feed = minimalFeed(entries = listOf(
            OpdsEntry(
                id = "urn:chaptervault:catalog",
                title = "Library",
                updated = "2026-07-08T12:00:00Z",
                summary = "Browse the manga library",
                links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/catalog", type = "application/atom+xml;profile=opds-catalog;kind=acquisition")),
            )
        ))
        val xml = serializer.serialize(feed)
        assertContains(xml, "<entry>")
        assertContains(xml, "urn:chaptervault:catalog")
        assertContains(xml, "Browse the manga library")
        assertContains(xml, "rel=\"subsection\"")
    }

    @Test
    fun `serializes PSE count attribute in pse namespace`() {
        val feed = minimalFeed(entries = listOf(
            OpdsEntry(
                id = "urn:chapter:1",
                title = "Chapter 1",
                updated = "2026-07-08T12:00:00Z",
                links = listOf(
                    OpdsLink(
                        rel = "http://vaemendis.net/opds-pse/stream",
                        href = "/opds/v1/chapters/1/pages/{pageNumber}",
                        type = "image/jpeg",
                        pseCount = 42,
                    )
                ),
            )
        ))
        val xml = serializer.serialize(feed)
        assertContains(xml, "42")
        assertContains(xml, "http://vaemendis.net/opds-pse/ns")
    }

    @Test
    fun `escapes XML-special characters in element text content`() {
        val feed = minimalFeed(
            title = "A & B",
            entries = listOf(
                OpdsEntry(
                    id = "urn:entry",
                    title = "Entry with <tags> and \"quotes\" and /slash/",
                    updated = "2026-07-08T12:00:00Z",
                    summary = "Summary & more < less > equal",
                    links = emptyList(),
                )
            ),
        )
        val xml = serializer.serialize(feed)
        assertContains(xml, "&amp;")
        assertContains(xml, "&lt;")
        assertFalse(xml.contains(">A & B<"), "raw & must not appear unescaped in element content")
        assertFalse(xml.contains("<tags>"), "raw < must not appear unescaped in element content")
    }
}
