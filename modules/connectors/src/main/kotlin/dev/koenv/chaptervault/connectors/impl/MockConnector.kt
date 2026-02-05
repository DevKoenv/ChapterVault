package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.execution.Executor
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mock connector for testing and demonstration.
 * Simulates a comic site with generated content.
 *
 * Note: This connector doesn't use execution plans - it generates mock data directly.
 * The executor is provided for interface compliance but is not used.
 */
class MockConnector(
    override val executor: Executor
) : Connector {
    
    override val config = ConnectorConfig(
        id = "mock-connector",
        name = "Mock Connector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 100.milliseconds  // Faster for testing
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
        "mock-comics.example.com"
    )

    /**
     * Uses default URL pattern matching from baseUrls
     *
     * Currently commented out to use the default implementation
     */
    // override fun canHandle(url: String): Boolean {}
    
    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        // Simulate network delay
        delay(100)
        
        return listOf(
            SeriesSearchResult(
                url = "https://mock-comics.example.com/series/test-comic-1",
                title = "Test Comic 1 - $query",
                description = "A test comic about $query",
                coverUrl = "https://mock-comics.example.com/covers/1.jpg"
            ),
            SeriesSearchResult(
                url = "https://mock-comics.example.com/series/test-comic-2",
                title = "Test Comic 2 - $query",
                description = "Another test comic about $query",
                coverUrl = "https://mock-comics.example.com/covers/2.jpg"
            )
        )
    }
    
    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        // Simulate network delay
        delay(100)
        
        val seriesId = seriesUrl.substringAfterLast("/")
        return SeriesMetadata(
            url = seriesUrl,
            title = "Mock Series: $seriesId",
            description = "This is a mock series for testing purposes. It demonstrates the connector architecture.",
            author = "Mock Author",
            coverUrl = "https://mock-comics.example.com/covers/$seriesId.jpg",
            tags = listOf("mock", "test", "demo"),
            status = SeriesStatus.ONGOING
        )
    }
    
    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        // Simulate network delay
        delay(100)
        
        // Generate 5 mock chapters
        return (1..5).map { chapterNum ->
            ChapterMetadata(
                url = "$seriesUrl/chapter-$chapterNum",
                seriesUrl = seriesUrl,
                title = "Chapter $chapterNum: The Adventure Continues",
                chapterNumber = chapterNum.toString(),
                publishDate = "2024-01-${String.format("%02d", chapterNum)}",
                pageCount = 3  // Each chapter has 3 pages
            )
        }
    }
    
    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        // Simulate network delay for discovering pages
        delay(200)
        
        // In a real connector, we would:
        // 1. Fetch the chapter page
        // 2. Parse page URLs (possibly handling tokens/CSRF)
        // 3. Download each page's binary data
        // 4. Pass to storage
        
        // For this mock, we'll generate 3 simple image pages
        val pageCount = 3
        
        for (pageIndex in 0 until pageCount) {
            // Simulate downloading a page
            delay(150)
            
            // Generate a mock image
            val imageBytes = generateMockImage(pageIndex)
            
            // Pass to storage
            storage.writePage(pageIndex, imageBytes, "image/png")
        }
    }
    
    /**
     * Generate a simple mock image with text indicating the page number
     */
    private fun generateMockImage(pageIndex: Int): ByteArray {
        val width = 800
        val height = 1200
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        
        // Fill background
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        
        // Draw page number
        graphics.color = Color.BLACK
        graphics.font = graphics.font.deriveFont(72f)
        val text = "Page ${pageIndex + 1}"
        val metrics = graphics.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        val y = height / 2
        graphics.drawString(text, x, y)
        
        graphics.dispose()
        
        // Convert to PNG bytes
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return outputStream.toByteArray()
    }
}
