package dev.koenv.chaptervault.extensions.opds

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series

class FeedBuilder {

    fun buildNavigationFeed(now: String): OpdsFeed = OpdsFeed(
        id = "urn:chaptervault:root",
        title = "ChapterVault",
        updated = now,
        selfHref = "/opds/v1",
        entries = listOf(
            OpdsEntry(
                id = "urn:chaptervault:catalog",
                title = "Library",
                updated = now,
                content = "Browse the manga library",
                links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/catalog", type = TYPE_ACQUISITION)),
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
            selfHref = "/opds/v1/catalog?page=$page&size=$size",
            nextHref = if (page + 1 < totalPages) "/opds/v1/catalog?page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/v1/catalog?page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            entries = series.map { buildSeriesEntry(it, now) },
        )
    }

    fun buildSeriesFeed(
        series: Series,
        chapters: List<Chapter>,
        now: String,
        pageInfoByChapterId: Map<String, ChapterPageInfo> = emptyMap(),
    ): OpdsFeed = OpdsFeed(
        id = "urn:chaptervault:series:${series.id}",
        title = series.title,
        updated = now,
        selfHref = "/opds/v1/series/${series.id}",
        entries = chapters.map { buildChapterEntry(it, now, pageInfoByChapterId[it.id.toString()]) },
    )

    private fun buildSeriesEntry(series: Series, now: String): OpdsEntry {
        val links = mutableListOf(
            OpdsLink(rel = "subsection", href = "/opds/v1/series/${series.id}", type = TYPE_ACQUISITION),
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

    internal fun buildChapterEntry(chapter: Chapter, now: String, pageInfo: ChapterPageInfo? = null): OpdsEntry {
        val links = mutableListOf<OpdsLink>()
        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED) {
            links.add(OpdsLink(rel = REL_ACQUISITION, href = "/opds/v1/download/${chapter.id}", type = TYPE_CBZ))
            if (pageInfo != null) {
                links.add(
                    OpdsLink(
                        rel = REL_PSE,
                        href = "/opds/v1/chapters/${chapter.id}/pages/{pageNumber}",
                        type = pageInfo.firstPageMimeType,
                        pseCount = pageInfo.pageCount,
                    )
                )
            }
        }
        return OpdsEntry(
            id = "urn:chaptervault:chapter:${chapter.id}",
            title = chapter.title,
            updated = now,
            links = links,
        )
    }

    data class ChapterPageInfo(val pageCount: Int, val firstPageMimeType: String)

    companion object {
        const val TYPE_NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
        const val TYPE_ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"
        const val TYPE_CBZ = "application/x-cbz"
        const val REL_IMAGE = "http://opds-spec.org/image"
        const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
        const val REL_PSE = "http://vaemendis.net/opds-pse/ns"
    }
}
