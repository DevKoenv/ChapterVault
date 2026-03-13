package com.example.mymodule

import dev.koenv.chaptervault.core.module.ModuleContext
import dev.koenv.chaptervault.core.module.ModuleEntrypoint
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class MyModule : ModuleEntrypoint {

    private var config = ModuleConfig()

    /**
     * Called before any module is enabled.
     * Read configuration and environment variables here — not in onEnable.
     * Other modules' connectors are not yet registered at this point.
     */
    override fun onLoad() {
        config = ModuleConfig(
            baseUrl = System.getenv("MY_MODULE_BASE_URL") ?: config.baseUrl,
            mirrorUrl = System.getenv("MY_MODULE_MIRROR_URL") ?: config.mirrorUrl,
            apiKey = System.getenv("MY_MODULE_API_KEY"),
        )
        val auth = if (config.apiKey != null) "API key configured" else "no auth"
        logger.info { "MyModule loading — base=${config.baseUrl}, mirror=${config.mirrorUrl}, $auth" }
    }

    /**
     * Register all contributions here: connectors, and in future releases routes and event listeners.
     * A single module can register as many connectors as needed.
     */
    override fun onEnable(context: ModuleContext) {
        // Main site connector
        context.registerConnector(MyConnector(context.executor, config))
        // Mirror site connector — same scraping logic, different domain
        context.registerConnector(MyMirrorConnector(context.executor, config))
    }

    /**
     * Release any resources held by this module.
     * Called in reverse load order so dependencies shut down after dependents.
     * Examples: close a database connection, cancel a background polling coroutine.
     */
    override fun onDisable() {
        logger.info { "MyModule disabled" }
    }
}
