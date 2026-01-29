package dev.koenv.chaptervault.opds.renderer

import dev.koenv.chaptervault.opds.model.OpdsFeed
import dev.koenv.chaptervault.opds.model.OpdsVersion

/**
 * Interface for rendering OPDS feeds to different formats
 */
interface FeedRenderer {
    /**
     * The OPDS version this renderer supports
     */
    val version: OpdsVersion

    /**
     * The content type for responses
     */
    val contentType: String

    /**
     * Render a feed to string output
     */
    fun render(feed: OpdsFeed): String
}

/**
 * Factory for creating renderers
 */
object FeedRendererFactory {
    private val renderers = mutableMapOf<OpdsVersion, () -> FeedRenderer>()

    init {
        register(OpdsVersion.V1_2) { Opds12Renderer() }
        // Future: register(OpdsVersion.V2_0) { Opds20Renderer() }
    }

    fun register(version: OpdsVersion, factory: () -> FeedRenderer) {
        renderers[version] = factory
    }

    fun create(version: OpdsVersion): FeedRenderer {
        return renderers[version]?.invoke()
            ?: throw IllegalArgumentException("No renderer registered for OPDS version: $version")
    }

    fun default(): FeedRenderer = create(OpdsVersion.V1_2)
}
