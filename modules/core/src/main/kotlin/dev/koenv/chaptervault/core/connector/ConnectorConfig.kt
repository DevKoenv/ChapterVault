package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig

/**
 * Configuration and capabilities for a connector.
 * Each connector defines its own config including rate limits and feature support.
 */
data class ConnectorConfig(
    /**
     * Display name of the connector (e.g., "Webtoon", "MangaPlus")
     */
    val name: String,

    /**
     * Version of the connector implementation
     */
    val version: String = "1.0.0",

    /**
     * Rate limiting configuration for this connector
     */
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(),

    /**
     * Feature flags indicating what this connector supports
     */
    val features: ConnectorFeatures = ConnectorFeatures(),

    /**
     * Priority for URL matching. Higher values = higher priority.
     * When multiple connectors match a URL, the highest priority wins.
     * Default is 0. Use negative values for fallback connectors.
     */
    val priority: Int = 0
)
