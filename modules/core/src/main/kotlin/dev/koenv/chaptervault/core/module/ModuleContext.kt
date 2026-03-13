package dev.koenv.chaptervault.core.module

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.execution.Executor

/**
 * Passed to [ChapterVaultModule.onEnable] — the single point through which a module
 * registers all of its contributions with the host application.
 *
 * Extension points are added here as explicit methods so module authors get
 * compile-time guidance on what is available. Each category of contribution
 * (connectors, routes, event listeners, …) is a separate method.
 *
 * Future extension points (not yet implemented):
 *   fun registerEventListener(listener: ModuleEventListener)
 *   fun registerRoutes(...)   // requires a route-abstraction layer, added when needed
 */
interface ModuleContext {
    /** The shared executor provided by the host — use this in all connectors. */
    val executor: Executor

    /** Register a connector to be included in the connector registry. */
    fun registerConnector(connector: Connector)
}
