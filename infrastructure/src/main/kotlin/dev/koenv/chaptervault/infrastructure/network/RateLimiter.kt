package dev.koenv.chaptervault.infrastructure.network

import kotlinx.coroutines.delay

class RateLimiter(
    private val requestsPerSecond: Double = 2.0,
) {
    private val delayMs: Long = (1000.0 / requestsPerSecond).toLong()

    suspend fun acquire() {
        delay(delayMs)
    }
}
