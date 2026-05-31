package dev.koenv.chaptervault.extensions.connectors

import dev.koenv.chaptervault.kernel.connector.DownloadPage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadPageTest {
    @Test
    fun `DownloadPage stores url and index`() {
        val page = DownloadPage(url = "https://example.com/page.jpg", index = 3)
        assertEquals("https://example.com/page.jpg", page.url)
        assertEquals(3, page.index)
    }

    @Test
    fun `DownloadPage headers default to empty`() {
        val page = DownloadPage(url = "https://example.com/page.jpg", index = 0)
        assertTrue(page.headers.isEmpty())
    }

    @Test
    fun `DownloadPage preserves custom headers`() {
        val page =
            DownloadPage(
                url = "https://cdn.example.com/page.jpg",
                index = 1,
                headers = mapOf("Referer" to "https://example.com", "X-Session" to "tok"),
            )
        assertEquals("https://example.com", page.headers["Referer"])
        assertEquals("tok", page.headers["X-Session"])
    }
}
