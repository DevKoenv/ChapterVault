package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.kernel.api.ChapterWithSeries
import dev.koenv.chaptervault.kernel.api.GenreCount
import dev.koenv.chaptervault.kernel.api.LanguageCount
import dev.koenv.chaptervault.kernel.api.RecentlyReadEntry
import dev.koenv.chaptervault.kernel.api.StatusCount
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import java.net.URLEncoder

class FeedBuilder {
    fun buildNavigationFeed(now: String): OpdsFeed =
        OpdsFeed(
            id = "urn:chaptervault:root",
            title = "ChapterVault",
            updated = now,
            selfHref = "/opds/v1",
            kind = "navigation",
            entries =
                listOf(
                    OpdsEntry(
                        id = "urn:chaptervault:catalog",
                        title = "Library",
                        updated = now,
                        content = "Browse the manga library",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/catalog", type = TYPE_ACQUISITION)),
                    ),
                    OpdsEntry(
                        id = "urn:chaptervault:history",
                        title = "Reading History",
                        updated = now,
                        content = "Series you have been reading",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/history", type = TYPE_ACQUISITION)),
                    ),
                    OpdsEntry(
                        id = "urn:chaptervault:library-updates",
                        title = "Library Updates",
                        updated = now,
                        content = "Recently added chapters",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/library-updates", type = TYPE_ACQUISITION)),
                    ),
                    OpdsEntry(
                        id = "urn:chaptervault:library:genres",
                        title = "Browse by Genre",
                        updated = now,
                        content = "Browse series by genre",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/library/genres", type = TYPE_NAVIGATION)),
                    ),
                    OpdsEntry(
                        id = "urn:chaptervault:library:languages",
                        title = "Browse by Language",
                        updated = now,
                        content = "Browse series by language",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/library/languages", type = TYPE_NAVIGATION)),
                    ),
                    OpdsEntry(
                        id = "urn:chaptervault:library:status",
                        title = "Browse by Status",
                        updated = now,
                        content = "Browse series by upstream status",
                        links = listOf(OpdsLink(rel = "subsection", href = "/opds/v1/library/status", type = TYPE_NAVIGATION)),
                    ),
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
            updated = series.maxOfOrNull { it.updatedAt }?.toString() ?: now,
            selfHref = "/opds/v1/catalog?page=$page&size=$size",
            kind = "acquisition",
            nextHref = if (page + 1 < totalPages) "/opds/v1/catalog?page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/v1/catalog?page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            entries = series.map { buildSeriesEntry(it) },
        )
    }

    fun buildHistoryFeed(
        entries: List<RecentlyReadEntry>,
        page: Int,
        size: Int,
        totalItems: Long,
        now: String,
    ): OpdsFeed {
        val totalPages = if (size > 0) (totalItems + size - 1) / size else 1
        return OpdsFeed(
            id = "urn:chaptervault:history",
            title = "Reading History",
            updated = now,
            selfHref = "/opds/v1/history?page=$page&size=$size",
            kind = "acquisition",
            nextHref = if (page + 1 < totalPages) "/opds/v1/history?page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/v1/history?page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            entries =
                entries.map { entry ->
                    val series = entry.series
                    val links =
                        mutableListOf(
                            OpdsLink(rel = "subsection", href = "/opds/v1/series/${series.id}", type = TYPE_ACQUISITION),
                        )
                    series.coverUrl?.let { links.add(OpdsLink(rel = REL_IMAGE, href = it, type = "image/jpeg")) }
                    OpdsEntry(
                        id = "urn:chaptervault:history:series:${series.id}",
                        title = series.title,
                        updated = entry.lastReadAt.toString(),
                        summary = "${entry.chaptersRead} of ${entry.totalChapters} chapters read",
                        links = links,
                    )
                },
        )
    }

    fun buildLibraryUpdatesFeed(
        chapters: List<ChapterWithSeries>,
        page: Int,
        size: Int,
        totalItems: Long,
        now: String,
    ): OpdsFeed {
        val totalPages = if (size > 0) (totalItems + size - 1) / size else 1
        return OpdsFeed(
            id = "urn:chaptervault:library-updates",
            title = "Library Updates",
            updated = now,
            selfHref = "/opds/v1/library-updates?page=$page&size=$size",
            kind = "acquisition",
            nextHref = if (page + 1 < totalPages) "/opds/v1/library-updates?page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/v1/library-updates?page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            entries =
                chapters.map { (chapter, series) ->
                    buildChapterEntry(chapter, coverUrl = series.coverUrl)
                },
        )
    }

    fun buildGenreNavFeed(
        genres: List<GenreCount>,
        now: String,
    ): OpdsFeed =
        OpdsFeed(
            id = "urn:chaptervault:library:genres",
            title = "Browse by Genre",
            updated = now,
            selfHref = "/opds/v1/library/genres",
            kind = "navigation",
            entries =
                genres.map { g ->
                    OpdsEntry(
                        id = "urn:chaptervault:library:genre:${g.genre}",
                        title = "${g.genre} (${g.seriesCount})",
                        updated = now,
                        links =
                            listOf(
                                OpdsLink(
                                    rel = "subsection",
                                    href = "/opds/v1/catalog?genre=${encodeParam(g.genre)}",
                                    type = TYPE_ACQUISITION,
                                ),
                            ),
                    )
                },
        )

    fun buildLanguageNavFeed(
        languages: List<LanguageCount>,
        now: String,
    ): OpdsFeed =
        OpdsFeed(
            id = "urn:chaptervault:library:languages",
            title = "Browse by Language",
            updated = now,
            selfHref = "/opds/v1/library/languages",
            kind = "navigation",
            entries =
                languages.map { l ->
                    OpdsEntry(
                        id = "urn:chaptervault:library:language:${l.language}",
                        title = "${l.language} (${l.seriesCount})",
                        updated = now,
                        links =
                            listOf(
                                OpdsLink(
                                    rel = "subsection",
                                    href = "/opds/v1/catalog?language=${encodeParam(l.language)}",
                                    type = TYPE_ACQUISITION,
                                ),
                            ),
                    )
                },
        )

    fun buildStatusNavFeed(
        statuses: List<StatusCount>,
        now: String,
    ): OpdsFeed =
        OpdsFeed(
            id = "urn:chaptervault:library:status",
            title = "Browse by Status",
            updated = now,
            selfHref = "/opds/v1/library/status",
            kind = "navigation",
            entries =
                statuses.map { s ->
                    OpdsEntry(
                        id = "urn:chaptervault:library:status:${s.status}",
                        title = "${s.status} (${s.seriesCount})",
                        updated = now,
                        links =
                            listOf(
                                OpdsLink(
                                    rel = "subsection",
                                    href = "/opds/v1/catalog?upstreamStatus=${encodeParam(s.status)}",
                                    type = TYPE_ACQUISITION,
                                ),
                            ),
                    )
                },
        )

    fun buildFilteredCatalogFeed(
        series: List<Series>,
        page: Int,
        size: Int,
        totalItems: Long,
        now: String,
        genre: String? = null,
        language: String? = null,
        upstreamStatus: String? = null,
    ): OpdsFeed {
        val totalPages = if (size > 0) (totalItems + size - 1) / size else 1
        val filterParams =
            buildList {
                genre?.let { add("genre=${encodeParam(it)}") }
                language?.let { add("language=${encodeParam(it)}") }
                upstreamStatus?.let { add("upstreamStatus=${encodeParam(it)}") }
            }.joinToString("&")
        val baseQuery = if (filterParams.isNotEmpty()) "$filterParams&" else ""
        val facets =
            buildList {
                genre?.let {
                    add(
                        OpdsFacet(
                            title = "Genre: $it",
                            href = "/opds/v1/catalog?genre=${encodeParam(it)}",
                            group = "Genre",
                            isActive = true,
                        ),
                    )
                    add(OpdsFacet(title = "All Genres", href = "/opds/v1/library/genres", group = "Genre"))
                }
                language?.let {
                    add(
                        OpdsFacet(
                            title = "Language: $it",
                            href = "/opds/v1/catalog?language=${encodeParam(it)}",
                            group = "Language",
                            isActive = true,
                        ),
                    )
                    add(OpdsFacet(title = "All Languages", href = "/opds/v1/library/languages", group = "Language"))
                }
                upstreamStatus?.let {
                    add(
                        OpdsFacet(
                            title = "Status: $it",
                            href = "/opds/v1/catalog?upstreamStatus=${encodeParam(it)}",
                            group = "Status",
                            isActive = true,
                        ),
                    )
                    add(OpdsFacet(title = "All Statuses", href = "/opds/v1/library/status", group = "Status"))
                }
            }
        return OpdsFeed(
            id = "urn:chaptervault:catalog:filtered",
            title = "Library",
            updated = now,
            selfHref = "/opds/v1/catalog?${baseQuery}page=$page&size=$size",
            kind = "acquisition",
            nextHref = if (page + 1 < totalPages) "/opds/v1/catalog?${baseQuery}page=${page + 1}&size=$size" else null,
            prevHref = if (page > 0) "/opds/v1/catalog?${baseQuery}page=${page - 1}&size=$size" else null,
            totalResults = totalItems,
            itemsPerPage = size,
            startIndex = page * size + 1,
            facets = facets,
            entries = series.map { buildSeriesEntry(it) },
        )
    }

    fun buildSeriesFeed(
        series: Series,
        chapters: List<Chapter>,
        pageInfoByChapterId: Map<String, ChapterPageInfo> = emptyMap(),
    ): OpdsFeed =
        OpdsFeed(
            id = "urn:chaptervault:series:${series.id}",
            title = series.title,
            updated = (chapters.map { it.updatedAt } + series.updatedAt).max().toString(),
            selfHref = "/opds/v1/series/${series.id}",
            kind = "acquisition",
            coverHref = series.coverUrl,
            entries = chapters.map { buildChapterEntry(it, pageInfoByChapterId[it.id.toString()], series.coverUrl) },
        )

    private fun buildSeriesEntry(series: Series): OpdsEntry {
        val links =
            mutableListOf(
                OpdsLink(rel = "subsection", href = "/opds/v1/series/${series.id}", type = TYPE_ACQUISITION),
            )
        series.coverUrl?.let { links.add(OpdsLink(rel = REL_IMAGE, href = it, type = "image/jpeg")) }
        return OpdsEntry(
            id = "urn:chaptervault:series:${series.id}",
            title = series.title,
            updated = series.updatedAt.toString(),
            summary = series.description,
            links = links,
        )
    }

    internal fun buildChapterEntry(
        chapter: Chapter,
        pageInfo: ChapterPageInfo? = null,
        coverUrl: String? = null,
    ): OpdsEntry {
        val links = mutableListOf<OpdsLink>()
        var summary: String? = null
        var content: String? = null

        if (chapter.downloadStatus == DownloadStatus.DOWNLOADED) {
            summary = "File Type: CBZ"
            content = TYPE_CBZ
            val thumbHref = "/opds/v1/chapters/${chapter.id}/pages/0"
            val pageType = pageInfo?.firstPageMimeType ?: "image/jpeg"
            links.add(OpdsLink(rel = REL_IMAGE, href = thumbHref, type = pageType))
            links.add(OpdsLink(rel = REL_IMAGE_THUMBNAIL, href = thumbHref, type = pageType))
            links.add(
                OpdsLink(
                    rel = REL_ACQUISITION,
                    href = "/opds/v1/download/${chapter.id}",
                    type = TYPE_CBZ,
                    pseCount = pageInfo?.pageCount,
                ),
            )
            if (pageInfo != null) {
                links.add(
                    OpdsLink(
                        rel = REL_PSE,
                        href = "/opds/v1/chapters/${chapter.id}/pages/{pageNumber}",
                        type = pageInfo.firstPageMimeType,
                        pseCount = pageInfo.pageCount,
                    ),
                )
            }
        } else if (coverUrl != null) {
            links.add(OpdsLink(rel = REL_IMAGE, href = coverUrl, type = "image/jpeg"))
            links.add(OpdsLink(rel = REL_IMAGE_THUMBNAIL, href = coverUrl, type = "image/jpeg"))
        }

        return OpdsEntry(
            id = "urn:chaptervault:chapter:${chapter.id}",
            title = chapter.title,
            updated = chapter.updatedAt.toString(),
            summary = summary,
            content = content,
            links = links,
        )
    }

    data class ChapterPageInfo(
        val pageCount: Int,
        val firstPageMimeType: String,
    )

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val TYPE_NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
        const val TYPE_ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"
        const val TYPE_CBZ = "application/x-cbz"
        const val REL_IMAGE = "http://opds-spec.org/image"
        const val REL_IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
        const val REL_ACQUISITION = "http://opds-spec.org/acquisition/open-access"
        const val REL_PSE = "http://vaemendis.net/opds-pse/stream"
    }
}
