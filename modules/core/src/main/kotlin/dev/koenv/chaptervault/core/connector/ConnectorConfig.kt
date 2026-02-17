package dev.koenv.chaptervault.core.connector

import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.ratelimit.SiteRateLimits

/**
 * Configuration and capabilities for a connector.
 * Each connector defines its own config including rate limits and feature support.
 */
data class ConnectorConfig(
    /**
     * Unique identifier for this connector (e.g., "asura-scans", "mangadex").
     * Should be lowercase, kebab-case, and stable across restarts.
     * Used for API lookups and configuration.
     */
    val id: String,

    /**
     * Display name of the connector (e.g., "Asura Scans", "MangaDex")
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
     * Domain-aware site-level rate limit configuration.
     *
     * Declares named buckets with specific rate limits. Instructions can tag themselves
     * with a bucket name via `rateLimitBucket`; untagged requests are auto-bucketed
     * by host with [SiteRateLimits.defaultLimits].
     */
    val siteRateLimits: SiteRateLimits = SiteRateLimits(),

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
