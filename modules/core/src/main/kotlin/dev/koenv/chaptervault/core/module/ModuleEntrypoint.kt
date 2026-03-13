package dev.koenv.chaptervault.core.module

interface ModuleEntrypoint {

    /**
     * Called early during startup, before any modules are enabled.
     * Use this for configuration parsing or declaring dependencies on other modules.
     * Do NOT register connectors, routes, or listeners here — use [onEnable] for that.
     */
    fun onLoad() {}

    /**
     * Called after all modules are loaded. Register connectors, routes, event listeners,
     * and any other contributions here via [context].
     */
    fun onEnable(context: ModuleContext)

    /**
     * Called during server shutdown. Release resources, cancel background tasks,
     * and flush any pending state. The default implementation is a no-op.
     */
    fun onDisable() {}
}
