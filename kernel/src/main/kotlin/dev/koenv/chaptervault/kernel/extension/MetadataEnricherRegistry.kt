package dev.koenv.chaptervault.kernel.extension

interface MetadataEnricherRegistry {
    fun register(
        enricher: MetadataEnricher,
        priority: Int = 100,
    )

    fun unregister(id: String)

    fun all(): List<MetadataEnricher>
}
