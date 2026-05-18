package dev.koenv.chaptervault.extensions.opds

class OpdsV1 {

    fun serialize(feed: OpdsFeed): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\"")
        append(" xmlns:opds=\"http://opds-spec.org/2010/catalog\"")
        append(" xmlns:os=\"http://a9.com/-/spec/opensearch/1.1/\">\n")
        append("<id>${esc(feed.id)}</id>\n")
        append("<title>${esc(feed.title)}</title>\n")
        append("<updated>${feed.updated}</updated>\n")
        append("<author><name>ChapterVault</name></author>\n")
        append("<link rel=\"self\" href=\"${esc(feed.selfHref)}\" type=\"application/atom+xml;profile=opds-catalog\"/>\n")
        append("<link rel=\"start\" href=\"/opds\" type=\"application/atom+xml;profile=opds-catalog;kind=navigation\"/>\n")
        feed.nextHref?.let {
            append("<link rel=\"next\" href=\"${esc(it)}\" type=\"application/atom+xml;profile=opds-catalog\"/>\n")
        }
        feed.prevHref?.let {
            append("<link rel=\"previous\" href=\"${esc(it)}\" type=\"application/atom+xml;profile=opds-catalog\"/>\n")
        }
        feed.totalResults?.let { append("<os:totalResults>$it</os:totalResults>\n") }
        feed.itemsPerPage?.let { append("<os:itemsPerPage>$it</os:itemsPerPage>\n") }
        feed.startIndex?.let { append("<os:startIndex>$it</os:startIndex>\n") }
        for (entry in feed.entries) {
            append("<entry>\n")
            append("<id>${esc(entry.id)}</id>\n")
            append("<title>${esc(entry.title)}</title>\n")
            append("<updated>${entry.updated}</updated>\n")
            entry.summary?.let { append("<summary>${esc(it)}</summary>\n") }
            entry.content?.let { append("<content type=\"text\">${esc(it)}</content>\n") }
            for (link in entry.links) {
                append("<link rel=\"${esc(link.rel)}\" href=\"${esc(link.href)}\" type=\"${esc(link.type)}\"/>\n")
            }
            append("</entry>\n")
        }
        append("</feed>")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
