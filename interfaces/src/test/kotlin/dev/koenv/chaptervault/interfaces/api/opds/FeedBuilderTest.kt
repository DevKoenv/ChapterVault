package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.kernel.api.ChapterWithSeries
import dev.koenv.chaptervault.kernel.api.GenreCount
import dev.koenv.chaptervault.kernel.api.LanguageCount
import dev.koenv.chaptervault.kernel.api.RecentlyReadEntry
import dev.koenv.chaptervault.kernel.api.StatusCount
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.utils.Id
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedBuilderTest {
    private val builder = FeedBuilder()
    private val now = "2026-07-10T12:00:00Z"
    private val seriesId = Id.from("00000000-0000-0000-0000-000000000001")
    private val chapterId = Id.from("00000000-0000-0000-0000-000000000002")

    private fun fakeSeries(
        title: String = "Test Series",
        coverUrl: String? = null,
    ) = Series(
        id = seriesId,
        title = title,
        connectorId = "mock",
        externalId = "ext-1",
        language = "en",
        status = SeriesStatus.IN_LIBRARY,
        autoDownload = false,
        defaultFormat = null,
        coverUrl = coverUrl,
        description = null,
        addedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun fakeChapter() =
        Chapter(
            id = chapterId,
            seriesId = seriesId,
            title = "Chapter 1",
            chapterIndex = 1.0,
            externalId = "ext-ch-1",
            downloadStatus = DownloadStatus.AVAILABLE,
            pageCount = null,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    @Test
    fun `entries are dated by the series and chapter they describe, not by the request time`() {
        val series = fakeSeries().copy(updatedAt = Instant.parse("2025-01-02T03:04:05Z"))
        val chapter = fakeChapter().copy(updatedAt = Instant.parse("2025-06-07T08:09:10Z"))

        val catalog = builder.buildCatalogFeed(listOf(series), 0, 20, 1, now)
        val seriesFeed = builder.buildSeriesFeed(series, listOf(chapter))

        assertEquals("2025-01-02T03:04:05Z", catalog.entries.single().updated)
        assertEquals("2025-06-07T08:09:10Z", seriesFeed.entries.single().updated)
        assertEquals("2025-06-07T08:09:10Z", seriesFeed.updated)
    }

    @Test
    fun `catalog feeds do not offer sort facets the server ignores`() {
        val catalog = builder.buildCatalogFeed(listOf(fakeSeries()), 0, 20, 1, now)
        val filtered =
            builder.buildFilteredCatalogFeed(
                listOf(fakeSeries()),
                0,
                20,
                1,
                now,
                genre = "Action",
                language = null,
                upstreamStatus = null,
            )

        assertEquals(emptyList(), catalog.facets.filter { "sort=" in it.href })
        assertEquals(emptyList(), filtered.facets.filter { "sort=" in it.href })
    }

    // ── buildNavigationFeed ────────────────────────────────────────────────

    @Test
    fun `buildNavigationFeed includes all six navigation entries`() {
        val feed = builder.buildNavigationFeed(now)
        val hrefs = feed.entries.flatMap { it.links }.map { it.href }
        assertContains(hrefs, "/opds/v1/catalog")
        assertContains(hrefs, "/opds/v1/history")
        assertContains(hrefs, "/opds/v1/library-updates")
        assertContains(hrefs, "/opds/v1/library/genres")
        assertContains(hrefs, "/opds/v1/library/languages")
        assertContains(hrefs, "/opds/v1/library/status")
        assertEquals(6, feed.entries.size)
    }

    // ── buildHistoryFeed ───────────────────────────────────────────────────

    @Test
    fun `buildHistoryFeed includes series title and read count summary`() {
        val entry =
            RecentlyReadEntry(
                series = fakeSeries("Manga A"),
                lastReadAt = Instant.EPOCH,
                chaptersRead = 3,
                totalChapters = 10,
            )
        val feed = builder.buildHistoryFeed(listOf(entry), page = 0, size = 20, totalItems = 1L, now = now)
        assertEquals(1, feed.entries.size)
        assertEquals("Manga A", feed.entries[0].title)
        assertNotNull(feed.entries[0].summary)
        assertContains(feed.entries[0].summary!!, "3")
        assertContains(feed.entries[0].summary!!, "10")
    }

    @Test
    fun `buildHistoryFeed entry links to series feed`() {
        val entry =
            RecentlyReadEntry(
                series = fakeSeries(),
                lastReadAt = Instant.EPOCH,
                chaptersRead = 1,
                totalChapters = 5,
            )
        val feed = builder.buildHistoryFeed(listOf(entry), page = 0, size = 20, totalItems = 1L, now = now)
        val link = feed.entries[0].links.first { it.rel == "subsection" }
        assertContains(link.href, "/opds/v1/series/$seriesId")
    }

    @Test
    fun `buildHistoryFeed includes cover link when series has coverUrl`() {
        val entry =
            RecentlyReadEntry(
                series = fakeSeries(coverUrl = "https://example.com/cover.jpg"),
                lastReadAt = Instant.EPOCH,
                chaptersRead = 1,
                totalChapters = 1,
            )
        val feed = builder.buildHistoryFeed(listOf(entry), page = 0, size = 20, totalItems = 1L, now = now)
        val imageLink = feed.entries[0].links.firstOrNull { it.rel == FeedBuilder.REL_IMAGE }
        assertNotNull(imageLink)
        assertEquals("https://example.com/cover.jpg", imageLink!!.href)
    }

    @Test
    fun `buildHistoryFeed emits next link when more pages exist`() {
        val feed = builder.buildHistoryFeed(emptyList(), page = 0, size = 5, totalItems = 10L, now = now)
        assertNotNull(feed.nextHref)
        assertContains(feed.nextHref!!, "page=1")
        assertNull(feed.prevHref)
    }

    @Test
    fun `buildHistoryFeed emits prev link on page 1`() {
        val feed = builder.buildHistoryFeed(emptyList(), page = 1, size = 5, totalItems = 10L, now = now)
        assertNotNull(feed.prevHref)
        assertContains(feed.prevHref!!, "page=0")
    }

    @Test
    fun `buildHistoryFeed emits no pagination links on single page`() {
        val feed = builder.buildHistoryFeed(emptyList(), page = 0, size = 20, totalItems = 3L, now = now)
        assertNull(feed.nextHref)
        assertNull(feed.prevHref)
    }

    // ── buildLibraryUpdatesFeed ────────────────────────────────────────────

    @Test
    fun `buildLibraryUpdatesFeed includes chapter entry`() {
        val item = ChapterWithSeries(chapter = fakeChapter(), series = fakeSeries())
        val feed = builder.buildLibraryUpdatesFeed(listOf(item), page = 0, size = 20, totalItems = 1L, now = now)
        assertEquals(1, feed.entries.size)
        assertEquals("Chapter 1", feed.entries[0].title)
    }

    @Test
    fun `buildLibraryUpdatesFeed selfHref uses library-updates path`() {
        val feed = builder.buildLibraryUpdatesFeed(emptyList(), page = 0, size = 20, totalItems = 0L, now = now)
        assertContains(feed.selfHref, "/opds/v1/library-updates")
    }

    @Test
    fun `buildLibraryUpdatesFeed pagination links preserve size param`() {
        val feed = builder.buildLibraryUpdatesFeed(emptyList(), page = 0, size = 10, totalItems = 25L, now = now)
        assertNotNull(feed.nextHref)
        assertContains(feed.nextHref!!, "size=10")
    }

    // ── buildGenreNavFeed ──────────────────────────────────────────────────

    @Test
    fun `buildGenreNavFeed creates one entry per genre with count in title`() {
        val genres = listOf(GenreCount("Action", 12), GenreCount("Romance", 5))
        val feed = builder.buildGenreNavFeed(genres, now)
        assertEquals(2, feed.entries.size)
        assertContains(feed.entries[0].title, "Action")
        assertContains(feed.entries[0].title, "12")
        assertContains(feed.entries[1].title, "Romance")
        assertContains(feed.entries[1].title, "5")
    }

    @Test
    fun `buildGenreNavFeed entry link points to filtered catalog`() {
        val feed = builder.buildGenreNavFeed(listOf(GenreCount("Action", 1)), now)
        val href = feed.entries[0].links[0].href
        assertContains(href, "/opds/v1/catalog")
        assertContains(href, "genre=Action")
    }

    @Test
    fun `buildGenreNavFeed URL-encodes genre names with spaces`() {
        val feed = builder.buildGenreNavFeed(listOf(GenreCount("Science Fiction", 3)), now)
        val href = feed.entries[0].links[0].href
        assertFalse(href.contains("Science Fiction"), "raw space must not appear in URL")
        assertContains(href, "Science")
    }

    // ── buildLanguageNavFeed ───────────────────────────────────────────────

    @Test
    fun `buildLanguageNavFeed creates entry per language with count`() {
        val languages = listOf(LanguageCount("en", 8), LanguageCount("ja", 3))
        val feed = builder.buildLanguageNavFeed(languages, now)
        assertEquals(2, feed.entries.size)
        assertContains(feed.entries[0].title, "en")
        assertContains(feed.entries[0].title, "8")
    }

    @Test
    fun `buildLanguageNavFeed entry link includes language param`() {
        val feed = builder.buildLanguageNavFeed(listOf(LanguageCount("ja", 1)), now)
        assertContains(feed.entries[0].links[0].href, "language=ja")
    }

    // ── buildStatusNavFeed ─────────────────────────────────────────────────

    @Test
    fun `buildStatusNavFeed creates entry per status with count`() {
        val statuses = listOf(StatusCount("ONGOING", 10), StatusCount("COMPLETED", 4))
        val feed = builder.buildStatusNavFeed(statuses, now)
        assertEquals(2, feed.entries.size)
        assertContains(feed.entries[0].title, "ONGOING")
        assertContains(feed.entries[0].title, "10")
    }

    @Test
    fun `buildStatusNavFeed entry link includes upstreamStatus param`() {
        val feed = builder.buildStatusNavFeed(listOf(StatusCount("COMPLETED", 2)), now)
        assertContains(feed.entries[0].links[0].href, "upstreamStatus=COMPLETED")
    }

    // ── buildFilteredCatalogFeed ───────────────────────────────────────────

    @Test
    fun `buildFilteredCatalogFeed selfHref includes all active filter params`() {
        val feed =
            builder.buildFilteredCatalogFeed(
                series = emptyList(),
                page = 0,
                size = 20,
                totalItems = 0L,
                now = now,
                genre = "Action",
                language = "en",
                upstreamStatus = "ONGOING",
            )
        assertContains(feed.selfHref, "genre=Action")
        assertContains(feed.selfHref, "language=en")
        assertContains(feed.selfHref, "upstreamStatus=ONGOING")
    }

    @Test
    fun `buildFilteredCatalogFeed pagination links preserve filter params`() {
        val feed =
            builder.buildFilteredCatalogFeed(
                series = emptyList(),
                page = 0,
                size = 5,
                totalItems = 10L,
                now = now,
                genre = "Action",
            )
        assertNotNull(feed.nextHref)
        assertContains(feed.nextHref!!, "genre=Action")
        assertContains(feed.nextHref!!, "page=1")
    }

    @Test
    fun `buildFilteredCatalogFeed with no filters produces clean selfHref`() {
        val feed =
            builder.buildFilteredCatalogFeed(
                series = emptyList(),
                page = 0,
                size = 20,
                totalItems = 0L,
                now = now,
            )
        assertFalse(feed.selfHref.contains("genre="))
        assertFalse(feed.selfHref.contains("language="))
        assertFalse(feed.selfHref.contains("upstreamStatus="))
        assertContains(feed.selfHref, "page=0")
    }
}
