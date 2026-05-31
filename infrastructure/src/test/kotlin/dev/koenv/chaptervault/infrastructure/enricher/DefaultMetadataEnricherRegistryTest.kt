package dev.koenv.chaptervault.infrastructure.enricher

import dev.koenv.chaptervault.kernel.extension.EnrichedMetadata
import dev.koenv.chaptervault.kernel.extension.EnricherInput
import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.shared.result.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultMetadataEnricherRegistryTest {
    private val registry = DefaultMetadataEnricherRegistry()

    @Test
    fun `register and retrieve enricher`() {
        val enricher = object : MetadataEnricher {
            override val id = "test.enricher"
            override suspend fun enrich(series: EnricherInput): Result<EnrichedMetadata> =
                Result.Success(EnrichedMetadata(author = "Test Author"))
        }
        registry.register(enricher, priority = 10)
        assertEquals(listOf(enricher), registry.all())
    }

    @Test
    fun `enrichers ordered by priority ascending`() {
        val low = object : MetadataEnricher {
            override val id = "low"
            override suspend fun enrich(s: EnricherInput) = Result.Success(EnrichedMetadata())
        }
        val high = object : MetadataEnricher {
            override val id = "high"
            override suspend fun enrich(s: EnricherInput) = Result.Success(EnrichedMetadata())
        }
        registry.register(low, priority = 200)
        registry.register(high, priority = 10)
        assertEquals(listOf(high, low), registry.all())
    }

    @Test
    fun `unregister removes enricher`() {
        val enricher = object : MetadataEnricher {
            override val id = "to.remove"
            override suspend fun enrich(s: EnricherInput) = Result.Success(EnrichedMetadata())
        }
        registry.register(enricher, priority = 100)
        registry.unregister("to.remove")
        assertTrue(registry.all().isEmpty())
    }
}
