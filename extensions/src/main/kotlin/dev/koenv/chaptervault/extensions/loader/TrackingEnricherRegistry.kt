package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry

class TrackingEnricherRegistry(
    private val delegate: MetadataEnricherRegistry,
) : MetadataEnricherRegistry by delegate {
    private val _registeredIds = mutableListOf<String>()
    val registeredIds: List<String> get() = _registeredIds.toList()

    override fun register(enricher: MetadataEnricher, priority: Int) {
        delegate.register(enricher, priority)
        _registeredIds.add(enricher.id)
    }
}
