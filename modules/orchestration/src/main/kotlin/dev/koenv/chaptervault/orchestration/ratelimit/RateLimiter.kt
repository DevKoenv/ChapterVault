package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of a single connector's rate limit state.
 */
data class ConnectorRateLimitSnapshot(
    val connectorName: String,
    val maxConcurrent: Int,
    val minDelayMs: Long,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long,
    val lastRequestTime: Long,
    val requestsInCurrentWindow: Int
)

/**
 * Point-in-time status of the orchestrator-level [RateLimiter].
 */
data class OrchestratorRateLimiterStatus(
    val connectors: List<ConnectorRateLimitSnapshot>
)

/**
 * Connector-level rate limiter applied by the Orchestrator around each connector method call.
 *
 * Delegates to [SlidingWindowLimiter] for the actual enforcement of concurrency,
 * minimum delay, and sliding window rate limiting.
 *
 * @param timeSource Injectable time provider for testability.
 */
class RateLimiter(
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    // Per-connector limiter instances, keyed by connector id
    private val connectorLimiters = ConcurrentHashMap<String, SlidingWindowLimiter>()

    /**
     * Pre-register a connector with an effective rate limit config.
     * Should be called at startup after applying any YAML configuration overrides,
     * before requests are made. If the connector is already registered, this is a no-op.
     */
    fun registerConnector(connectorName: String, config: RateLimitConfig) {
        connectorLimiters.getOrPut(connectorName) {
            SlidingWindowLimiter(connectorName, config, timeSource)
        }
    }

    /**
     * Execute a block with rate limiting applied.
     * This is the preferred API — wraps the entire operation with rate limit enforcement.
     */
    suspend fun <T> withRateLimit(connector: Connector, block: suspend () -> T): T {
        val limiter = getOrCreateLimiter(connector)
        return limiter.withPermit(block = block)
    }

    /**
     * Get or create a limiter for a connector.
     * Uses connector id as key for uniqueness.
     */
    private fun getOrCreateLimiter(connector: Connector): SlidingWindowLimiter {
        val key = connector.config.id
        return connectorLimiters.getOrPut(key) {
            SlidingWindowLimiter(key, connector.config.rateLimitConfig, timeSource)
        }
    }

    suspend fun getStatus(): OrchestratorRateLimiterStatus {
        val snapshots = connectorLimiters.map { (name, limiter) ->
            val s = limiter.snapshot()
            ConnectorRateLimitSnapshot(
                connectorName = name,
                maxConcurrent = s.maxConcurrent,
                minDelayMs = s.minDelayMs,
                maxRequestsPerWindow = s.maxRequestsPerWindow,
                windowDurationMs = s.windowDurationMs,
                lastRequestTime = s.lastRequestTime,
                requestsInCurrentWindow = s.requestsInCurrentWindow
            )
        }

        return OrchestratorRateLimiterStatus(connectors = snapshots)
    }
}
