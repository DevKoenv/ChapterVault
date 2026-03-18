package dev.koenv.chaptervault.core.addon

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.execution.Executor
import java.io.File

/**
 * Passed to [AddonEntrypoint.onEnable] — the single point through which an addon
 * registers all of its contributions with the host application.
 *
 * Extension points are added here as explicit methods so addon authors get
 * compile-time guidance on what is available. Each category of contribution
 * (connectors, routes, event listeners, …) is a separate method.
 */
interface AddonContext {
    /** The stable addon identifier from addon.yml. */
    val addonId: String

    /** The human-readable addon name from addon.yml. */
    val addonName: String

    /** The addon version string from addon.yml. */
    val addonVersion: String

    /** The shared executor provided by the host — use this in all connectors. */
    val executor: Executor

    /**
     * Persistent data directory for this addon: `<addonsDataPath>/<addonId>/data/`.
     * Created on first access. Use this to store addon-specific files between restarts.
     */
    val dataDir: File

    /** Register a connector to be included in the connector registry. */
    fun registerConnector(connector: Connector)
}
