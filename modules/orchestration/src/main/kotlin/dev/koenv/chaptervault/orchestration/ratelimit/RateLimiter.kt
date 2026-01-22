package dev.koenv.chaptervault.orchestration.ratelimit

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rate limiter that enforces delays and concurrency limits per connector
 */
class RateLimiter {
    
    private val connectorStates = mutableMapOf<Connector, ConnectorState>()
    private val mutex = Mutex()
    
    /**
     * Acquire permission to make a request for this connector.
     * Blocks until rate limit allows the request.
     */
    suspend fun acquire(connector: Connector, config: RateLimitConfig) {
        val delayNeeded = mutex.withLock {
            val state = connectorStates.getOrPut(connector) { ConnectorState() }
            
            // Calculate delay needed since last request
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - state.lastRequestTime
            val delay = config.minDelay.inWholeMilliseconds - timeSinceLastRequest
            
            // Return delay needed
            maxOf(0, delay)
        }
        
        // Delay OUTSIDE the mutex lock to avoid blocking other coroutines
        if (delayNeeded > 0) {
            delay(delayNeeded.milliseconds)
        }
        
        // Update last request time AFTER the delay
        mutex.withLock {
            val state = connectorStates.getOrPut(connector) { ConnectorState() }
            state.lastRequestTime = System.currentTimeMillis()
        }
        
        // TODO: Implement maxConcurrent and window-based rate limiting
    }
    
    private data class ConnectorState(
        var lastRequestTime: Long = 0L
    )
}
