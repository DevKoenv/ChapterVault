package com.example.mymodule

/**
 * Configuration for this module, populated from environment variables in [MyModule.onLoad].
 * Passed into each connector so they share the same settings without global state.
 */
data class ModuleConfig(
    /** Base URL of the main site. Override with MY_MODULE_BASE_URL. */
    val baseUrl: String = "https://example.com",
    /** Base URL of the mirror site. Override with MY_MODULE_MIRROR_URL. */
    val mirrorUrl: String = "https://mirror.example.com",
    /** Optional API key sent as X-API-Key on every request. Set via MY_MODULE_API_KEY. */
    val apiKey: String? = null,
)
