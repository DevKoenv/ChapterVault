package com.example.myaddon

/**
 * Configuration for this addon, populated from environment variables in [MyAddon.onLoad].
 * Passed into each connector so they share the same settings without global state.
 */
data class AddonConfig(
    /** Base URL of the main site. Override with MY_ADDON_BASE_URL. */
    val baseUrl: String = "https://example.com",
    /** Base URL of the mirror site. Override with MY_ADDON_MIRROR_URL. */
    val mirrorUrl: String = "https://mirror.example.com",
    /** Optional API key sent as X-API-Key on every request. Set via MY_ADDON_API_KEY. */
    val apiKey: String? = null,
)
