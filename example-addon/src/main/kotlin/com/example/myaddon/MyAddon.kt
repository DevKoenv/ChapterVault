package com.example.myaddon

import dev.koenv.chaptervault.core.addon.AddonContext
import dev.koenv.chaptervault.core.addon.AddonEntrypoint
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class MyAddon : AddonEntrypoint {

    private var config = AddonConfig()

    /**
     * Called before any addon is enabled.
     * Read configuration and environment variables here — not in onEnable.
     * Other addons' connectors are not yet registered at this point.
     */
    override fun onLoad() {
        config = AddonConfig(
            baseUrl = System.getenv("MY_ADDON_BASE_URL") ?: config.baseUrl,
            mirrorUrl = System.getenv("MY_ADDON_MIRROR_URL") ?: config.mirrorUrl,
            apiKey = System.getenv("MY_ADDON_API_KEY"),
        )
        val auth = if (config.apiKey != null) "API key configured" else "no auth"
        logger.info { "MyAddon loading — base=${config.baseUrl}, mirror=${config.mirrorUrl}, $auth" }
    }

    /**
     * Register all contributions here: connectors, and in future releases routes and event listeners.
     * A single addon can register as many connectors as needed.
     */
    override fun onEnable(context: AddonContext) {
        // Main site connector
        context.registerConnector(MyConnector(context.executor, config))
        // Mirror site connector — same scraping logic, different domain
        context.registerConnector(MyMirrorConnector(context.executor, config))
    }

    /**
     * Release any resources held by this addon.
     * Called in reverse load order so dependencies shut down after dependents.
     * Examples: close a database connection, cancel a background polling coroutine.
     */
    override fun onDisable() {
        logger.info { "MyAddon disabled" }
    }
}
