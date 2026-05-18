package dev.koenv.chaptervault.extensions.opds

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series

class FeedBuilder {

    fun buildNavigationFeed(now: String): OpdsFeed = OpdsFeed(
        id = "urn:chaptervault:root",
        title = "ChapterVault",
        updated = now,
        selfHref = "/opds",
        entries = listOf(
            OpdsEntry(
                id = "urn:chaptervault:catalog",
                title = "Library",
                updated = now,
                content = "Browse the manga library",
                links = listOf(OpdsLink(rel = "subsection", href = "/opds/catalog", type = TYPE_ACQUISITION)),
            )
        ),
    )

    fun buildCatalogFeed(
        series: List<Series>,
        page: Int,
        size: Int,
        totalItems: Long,
        now: String,
    ): OpdsFeed {
        val totalPages = if (size > 0) (totalItems + size - 1) / size else 1
        return OpdsFeed(
            id = "urn:chaptervault:catalog",
            title = "ChapterVault Library",
            updated = now,
            selfHref = "/opds/catalog?page=$page&size=$size",
            nextHref = if (page + 1 < totalPages) "/opds/catalog?page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/catalog?page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            entries = series.map { buildSeriesEntry(it, now) },
        )
    }

    fun buildSeriesFeed(series: Series, chapters: List<Chapter>, now: String): OpdsFeed = OpdsFeed(
        id = "urn:chaptervault:series:${series.id}",
        title = series.title,
        updated = now,
        selfHref = "/opds/series/${series.id}",
        entries = chapters.map { buildChapterEntry(it, now) },
    )

    private fun buildSeriesEntry(series: Series, now: String): OpdsEntry {
        val links = mutableListOf(
            OpdsLink(rel = "subsection", href = "/opds/series/${series.id}", type = TYPE_ACQUISITION),
        )
        series.coverUrl?.let { links.add(OpdsLink(rel = REL_IMAGE, href = it, type = "image/jpeg")) }
        return OpdsEntry(
            id = "urn:chaptervault:series:${series.id}",
            title = series.title,
            updated = now,
            summary = series.description,
            links = links,
        )
    }

    private fun buildChapterEntry(chapter: Chapter, now: String): OpdsEntry {
        val links = mutableListOf<OpdsLink>()
        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED) {
            links.add(OpdsLink(rel = REL_ACQUISITION, href = "/opds/download/${chapter.id}", type = TYPE_CBZ))
        }
        return OpdsEntry(
            id = "urn:chaptervault:chapter:${chapter.id}",
            title = chapter.title,
            updated = now,
            links = links,
        )
    }

    companion object {
        const val TYPE_NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
        const val TYPE_ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"
        const val TYPE_CBZ = "application/x-cbz"
        const val REL_IMAGE = "http://opds-spec.org/image"
        const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    }
}
