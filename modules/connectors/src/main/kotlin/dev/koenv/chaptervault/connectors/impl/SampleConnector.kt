package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

/**
 * Mock connector using a shared in-memory list to simulate an API.
 */
class SampleConnector : Connector {

    private val logger = org.slf4j.LoggerFactory.getLogger(SampleConnector::class.java)

    override val config = ConnectorConfig(
        name = "SampleConnector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 1.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = false,
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 3
        )
    )

    override val baseUrls = listOf(
        "https://example.net/*",
        "https://sample-comics.example.net/*"
    )

    /**
     * Shared in-memory "database" of series
     */
    private val seriesList = listOf(
        SeriesMetadata(
            url = "https://sample-comics.example.net/series/test-comic-1",
            title = "Sample adventures of a comic",
            description = "A thrilling comic series about adventures.",
            author = "Author A",
            coverUrl = "https://sample-comics.example.net/covers/1.jpg",
            tags = listOf("adventure", "mock", "demo"),
            status = SeriesStatus.ONGOING
        ),
        SeriesMetadata(
            url = "https://sample-comics.example.net/series/test-comic-2",
            title = "Some fantasy tales",
            description = "Dive into a world of fantasy and magic.",
            author = "Author B",
            coverUrl = "https://sample-comics.example.net/covers/2.jpg",
            tags = listOf("fantasy", "magic", "demo"),
            status = SeriesStatus.COMPLETED
        )
    )

    /**
     * Series metadata -> chapter list mapping
     */
    private val chapterMap: Map<String, List<ChapterMetadata>> = seriesList.associate { series ->
        series.url to (1..5).map { chapterNum ->
            ChapterMetadata(
                url = "${series.url}/chapter-$chapterNum",
                seriesUrl = series.url,
                title = "Chapter $chapterNum of ${series.title}",
                chapterNumber = chapterNum.toString(),
                publishDate = "2024-01-${String.format("%02d", chapterNum)}",
                pageCount = 3
            )
        }
    }

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Searching for: {}", query)
        delay(100) // simulate network delay

        val results = seriesList
            .filter { it.title.contains(query, ignoreCase = true) }
            .map {
                SeriesSearchResult(
                    url = it.url,
                    title = it.title,
                    description = it.description,
                    coverUrl = it.coverUrl
                )
            }
        logger.info("Search found {} results for '{}'", results.size, query)
        return results
    }

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Fetching series metadata for: {}", seriesUrl)
        delay(100) // simulate network delay

        val series = seriesList.find { it.url == seriesUrl }
            ?: throw IllegalArgumentException("Series not found: $seriesUrl")
        logger.info("Found series: {}", series.title)
        return series
    }

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Fetching chapter list for: {}", seriesUrl)
        delay(100)

        val chapters = chapterMap[seriesUrl]
            ?: throw IllegalArgumentException("No chapters found for series: $seriesUrl")
        logger.info("Found {} chapters for series", chapters.size)
        return chapters
    }

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Downloading chapter: {}", chapterUrl)
        delay(200) // simulate network delay for discovering pages

        val pageCount = 3
        for (pageIndex in 0 until pageCount) {
            logger.debug("Downloading page {}/{}", pageIndex + 1, pageCount)
            delay(150)
            val imageBytes = generateMockImage(pageIndex)
            storage.writePage(pageIndex, imageBytes, "image/png")
        }
        logger.info("Chapter download complete: {}", chapterUrl)
    }

    private fun generateMockImage(pageIndex: Int): ByteArray {
        val width = 800
        val height = 1200
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)

        graphics.color = Color.BLACK
        graphics.font = graphics.font.deriveFont(72f)
        val text = "Page ${pageIndex + 1}"
        val metrics = graphics.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        val y = height / 2
        graphics.drawString(text, x, y)

        graphics.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return outputStream.toByteArray()
    }
}
