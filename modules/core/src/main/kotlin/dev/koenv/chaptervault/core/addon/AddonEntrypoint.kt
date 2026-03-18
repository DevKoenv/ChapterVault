package dev.koenv.chaptervault.core.addon

interface AddonEntrypoint {

    /**
     * Called early during startup, before any addons are enabled.
     * Use this for configuration parsing or declaring dependencies on other addons.
     * Do NOT register connectors, routes, or listeners here — use [onEnable] for that.
     */
    fun onLoad() {}

    /**
     * Called after all addons are loaded. Register connectors, routes, event listeners,
     * and any other contributions here via [context].
     */
    fun onEnable(context: AddonContext)

    /**
     * Called during server shutdown. Release resources, cancel background tasks,
     * and flush any pending state. The default implementation is a no-op.
     */
    fun onDisable() {}
}
