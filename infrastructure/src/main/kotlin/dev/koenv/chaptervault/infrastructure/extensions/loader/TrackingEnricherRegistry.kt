package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.extension.MetadataEnricher
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry

class TrackingEnricherRegistry(
    private val delegate: MetadataEnricherRegistry,
) : MetadataEnricherRegistry by delegate {
    private val _registeredIds = mutableListOf<String>()
    val registeredIds: List<String> get() = _registeredIds.toList()

    override fun register(
        enricher: MetadataEnricher,
        priority: Int,
    ) {
        check(delegate.all().none { it.id == enricher.id }) { "A metadata enricher with id '${enricher.id}' is already registered" }
        delegate.register(enricher, priority)
        _registeredIds.add(enricher.id)
    }
}
