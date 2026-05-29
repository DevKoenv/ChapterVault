package dev.koenv.chaptervault.shared.ratelimit

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(
    private val requestsPerSecond: Double,
    private val burst: Int = maxOf(1, requestsPerSecond.toInt()),
) {
    private val mutex = Mutex()
    private val windowMs = (burst / requestsPerSecond * 1000).toLong()
    private val timestamps = ArrayDeque<Long>(burst + 1)

    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            timestamps.removeAll { it < now - windowMs }
            if (timestamps.size >= burst) {
                val waitMs = windowMs - (now - timestamps.first()) + 1L
                delay(waitMs)
                timestamps.removeAll { it < System.currentTimeMillis() - windowMs }
            }
            timestamps.addLast(System.currentTimeMillis())
        }
    }

    suspend fun tryAcquire(): Long =
        mutex.withLock {
            val now = System.currentTimeMillis()
            timestamps.removeAll { it < now - windowMs }
            if (timestamps.size >= burst) {
                windowMs - (now - timestamps.first()) + 1L
            } else {
                timestamps.addLast(now)
                0L
            }
        }
}
