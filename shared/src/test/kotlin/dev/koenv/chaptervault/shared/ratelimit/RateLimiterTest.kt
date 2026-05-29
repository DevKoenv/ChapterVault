package dev.koenv.chaptervault.shared.ratelimit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `tryAcquire returns 0 when under limit`() = runBlocking {
        val limiter = RateLimiter(requestsPerSecond = 10.0, burst = 5)
        assertEquals(0L, limiter.tryAcquire())
        assertEquals(0L, limiter.tryAcquire())
    }

    @Test
    fun `tryAcquire returns positive ms when burst exhausted`() = runBlocking {
        val limiter = RateLimiter(requestsPerSecond = 1.0, burst = 2)
        assertEquals(0L, limiter.tryAcquire())
        assertEquals(0L, limiter.tryAcquire())
        val waitMs = limiter.tryAcquire()
        assertTrue(waitMs > 0, "Expected positive wait time after burst exhausted, got $waitMs")
    }

    @Test
    fun `tryAcquire does not record the attempt when rejected`() = runBlocking {
        val limiter = RateLimiter(requestsPerSecond = 1.0, burst = 1)
        assertEquals(0L, limiter.tryAcquire())
        val firstReject = limiter.tryAcquire()
        assertTrue(firstReject > 0)
        val secondReject = limiter.tryAcquire()
        assertTrue(secondReject > 0)
    }
}
