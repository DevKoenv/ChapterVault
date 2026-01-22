package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig

/**
 * Configuration and capabilities for a connector.
 * Each connector defines its own config including rate limits and feature support.
 */
data class ConnectorConfig(
    /**
     * Display name of the connector (e.g., "MangaDex", "ComicVine")
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
    val features: ConnectorFeatures = ConnectorFeatures()
)
