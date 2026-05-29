package dev.koenv.chaptervault.extensions.connectors

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BucketTest {
    @Test
    fun `Bucket API has stable id 'api'`() {
        assertEquals("api", Bucket.API.id)
    }

    @Test
    fun `Bucket CDN has stable id 'cdn'`() {
        assertEquals("cdn", Bucket.CDN.id)
    }

    @Test
    fun `BucketConfig defaults burst to requestsPerSecond rounded down`() {
        val config = BucketConfig(requestsPerSecond = 3.7)
        assertEquals(3, config.burst)
    }

    @Test
    fun `BucketConfig burst minimum is 1`() {
        val config = BucketConfig(requestsPerSecond = 0.5)
        assertEquals(1, config.burst)
    }

    @Test
    fun `BucketConfig explicit burst overrides default`() {
        val config = BucketConfig(requestsPerSecond = 5.0, burst = 10)
        assertEquals(10, config.burst)
    }
}
