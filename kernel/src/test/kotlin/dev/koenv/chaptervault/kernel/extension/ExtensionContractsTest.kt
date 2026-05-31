package dev.koenv.chaptervault.kernel.extension

import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionContractsTest {
    @Test
    fun `EnrichedMetadata defaults are empty`() {
        val m = EnrichedMetadata()
        assertNull(m.author)
        assertNull(m.artist)
        assertNull(m.year)
        assertNull(m.upstreamStatus)
        assertEquals(emptyList(), m.genres)
    }

    @Test
    fun `ExtensionConfigField stores all fields`() {
        val field = ExtensionConfigField("api_key", "API Key", ConfigFieldType.PASSWORD, required = true)
        assertEquals("api_key", field.key)
        assertEquals(ConfigFieldType.PASSWORD, field.type)
    }

    @Test
    fun `NotificationEvent builds correctly`() {
        val event = NotificationEvent(
            seriesId = "s1",
            seriesTitle = "My Manga",
            newChapters = listOf(NotificationEvent.ChapterSummary("c1", "Ch 1", 1.0)),
        )
        assertEquals(1, event.newChapters.size)
    }

    @Test
    fun `ExtensionConfig getOrDefault returns value when present`() {
        val config = object : ExtensionConfig {
            override fun get(key: String) = if (key == "x") "hello" else null
        }
        assertEquals("hello", config.getOrDefault("x", "fallback"))
        assertEquals("fallback", config.getOrDefault("missing", "fallback"))
    }
}
