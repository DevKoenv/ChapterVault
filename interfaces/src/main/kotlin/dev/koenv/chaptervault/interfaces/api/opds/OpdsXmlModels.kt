package dev.koenv.chaptervault.interfaces.api.opds

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

private const val ATOM_NS = "http://www.w3.org/2005/Atom"
private const val OS_NS = "http://a9.com/-/spec/opensearch/1.1/"
private const val PSE_NS = "http://vaemendis.net/opds-pse/ns"

@Serializable
@XmlSerialName("link", ATOM_NS, "")
data class OpdsLinkXml(
    val rel: String,
    val href: String,
    val type: String,
    @XmlSerialName("count", PSE_NS, "pse")
    val pseCount: Int? = null,
)

@Serializable
@XmlSerialName("name", ATOM_NS, "")
data class OpdsAuthorNameXml(
    @XmlValue val value: String = "ChapterVault",
)

@Serializable
@XmlSerialName("author", ATOM_NS, "")
data class OpdsAuthorXml(
    val name: OpdsAuthorNameXml = OpdsAuthorNameXml(),
)

@Serializable
@XmlSerialName("content", ATOM_NS, "")
data class OpdsContentXml(
    val type: String,
    @XmlValue val value: String,
)

@Serializable
@XmlSerialName("entry", ATOM_NS, "")
data class OpdsEntryXml(
    @XmlElement(true) val id: String,
    @XmlElement(true) val title: String,
    @XmlElement(true) val updated: String,
    @XmlElement(true) val summary: String? = null,
    val content: OpdsContentXml? = null,
    val links: List<OpdsLinkXml> = emptyList(),
)

@Serializable
@XmlSerialName("feed", ATOM_NS, "")
data class OpdsFeedXml(
    @XmlElement(true) val id: String,
    @XmlElement(true) val title: String,
    @XmlElement(true) val updated: String,
    val author: OpdsAuthorXml = OpdsAuthorXml(),
    val links: List<OpdsLinkXml> = emptyList(),
    @XmlSerialName("totalResults", OS_NS, "os")
    @XmlElement(true) val totalResults: Long? = null,
    @XmlSerialName("itemsPerPage", OS_NS, "os")
    @XmlElement(true) val itemsPerPage: Int? = null,
    @XmlSerialName("startIndex", OS_NS, "os")
    @XmlElement(true) val startIndex: Int? = null,
    val entries: List<OpdsEntryXml> = emptyList(),
) {
    companion object {
        fun from(feed: OpdsFeed): OpdsFeedXml {
            val feedLinks = buildList {
                add(OpdsLinkXml(
                    rel = "self",
                    href = feed.selfHref,
                    type = "application/atom+xml;profile=opds-catalog;kind=${feed.kind}",
                ))
                add(OpdsLinkXml(
                    rel = "start",
                    href = "/opds/v1",
                    type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                ))
                feed.coverHref?.let {
                    add(OpdsLinkXml(rel = "http://opds-spec.org/image", href = it, type = "image/jpeg"))
                }
                feed.nextHref?.let {
                    add(OpdsLinkXml(rel = "next", href = it, type = "application/atom+xml;profile=opds-catalog"))
                }
                feed.prevHref?.let {
                    add(OpdsLinkXml(rel = "previous", href = it, type = "application/atom+xml;profile=opds-catalog"))
                }
            }
            return OpdsFeedXml(
                id = feed.id,
                title = feed.title,
                updated = feed.updated,
                links = feedLinks,
                totalResults = feed.totalResults,
                itemsPerPage = feed.itemsPerPage,
                startIndex = feed.startIndex,
                entries = feed.entries.map { entry ->
                    OpdsEntryXml(
                        id = entry.id,
                        title = entry.title,
                        updated = entry.updated,
                        summary = entry.summary,
                        content = entry.content?.let { OpdsContentXml(type = "text", value = it) },
                        links = entry.links.map { link ->
                            OpdsLinkXml(
                                rel = link.rel,
                                href = link.href,
                                type = link.type,
                                pseCount = link.pseCount,
                            )
                        },
                    )
                },
            )
        }
    }
}
