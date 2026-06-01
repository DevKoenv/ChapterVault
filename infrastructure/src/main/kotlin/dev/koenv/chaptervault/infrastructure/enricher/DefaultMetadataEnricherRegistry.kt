package dev.koenv.chaptervault.infrastructure.enricher

import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import java.util.concurrent.CopyOnWriteArrayList

class DefaultMetadataEnricherRegistry : MetadataEnricherRegistry {
    private data class Entry(
        val enricher: MetadataEnricher,
        val priority: Int,
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    @Synchronized
    override fun register(
        enricher: MetadataEnricher,
        priority: Int,
    ) {
        entries.removeIf { it.enricher.id == enricher.id }
        entries.add(Entry(enricher, priority))
    }

    @Synchronized
    override fun unregister(id: String) {
        entries.removeIf { it.enricher.id == id }
    }

    override fun all(): List<MetadataEnricher> = entries.sortedBy { it.priority }.map { it.enricher }
}
