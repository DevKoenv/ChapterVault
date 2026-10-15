package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.infrastructure.config.EndpointLimitConfig
import dev.koenv.chaptervault.infrastructure.config.RateLimitConfig
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthRateLimitPluginTest {
    // the test engine's peer is the loopback address
    private val loopback = listOf("127.0.0.0/8")

    private fun config(
        enabled: Boolean = true,
        trustedNetworks: List<String> = emptyList(),
        trustedProxies: List<String> = emptyList(),
    ) = RateLimitConfig(
        enabled = enabled,
        trustedNetworks = trustedNetworks,
        trustedProxies = trustedProxies,
        login = EndpointLimitConfig(maxAttempts = 3, windowMinutes = 60),
        register = EndpointLimitConfig(maxAttempts = 2, windowMinutes = 60),
    )

    private fun ApplicationTestBuilder.setup(rateLimit: RateLimitConfig) {
        application {
            install(AuthRateLimiting) { config = rateLimit }
            routing {
                post("/api/v1/auth/login") { call.respondText("login") }
                post("/api/v1/auth/register") { call.respondText("register") }
                post("/api/v1/other") { call.respondText("other") }
            }
        }
    }

    private suspend fun HttpClient.attempt(
        path: String,
        forwardedFor: String? = null,
    ): HttpResponse =
        post(path) {
            if (forwardedFor != null) header("X-Forwarded-For", forwardedFor)
        }

    @Test
    fun `requests under the limit pass`() =
        testApplication {
            setup(config())
            repeat(3) {
                val response = client.attempt("/api/v1/auth/login")
                assertEquals(HttpStatusCode.OK, response.status)
                assertNull(response.headers[HttpHeaders.RetryAfter])
            }
        }

    @Test
    fun `requests over the limit get 429 with Retry-After`() =
        testApplication {
            setup(config())
            repeat(3) { client.attempt("/api/v1/auth/login") }
            val response = client.attempt("/api/v1/auth/login")
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val retryAfter = assertNotNull(response.headers[HttpHeaders.RetryAfter]).toLong()
            assertTrue(retryAfter > 0)
        }

    @Test
    fun `login and register are limited separately`() =
        testApplication {
            setup(config())
            repeat(3) { client.attempt("/api/v1/auth/login") }
            assertEquals(HttpStatusCode.TooManyRequests, client.attempt("/api/v1/auth/login").status)

            // login is exhausted, register still has its own budget of 2
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/register").status)
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/register").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.attempt("/api/v1/auth/register").status)
        }

    @Test
    fun `a login path spelled differently cannot dodge the limit`() {
        // each spelling that reaches the login route at all must be counted like the plain one
        for (path in listOf("/api/v1/auth/logi%6E", "/api/v1/auth//login", "/api/v1/auth/%6Cogin", "/api/v1/auth/regist%65r")) {
            testApplication {
                setup(config())
                val responses = List(5) { client.attempt(path) }
                val reached = responses.count { it.status == HttpStatusCode.OK }
                // login allows 3 attempts, register 2
                assertTrue(reached <= 3, "$path reached its route $reached times: ${responses.map { it.status.value }}")
            }
        }
    }

    @Test
    fun `other endpoints are not limited`() =
        testApplication {
            setup(config())
            repeat(10) { assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/other").status) }
        }

    @Test
    fun `trusted network clients are exempt`() =
        testApplication {
            setup(config(trustedNetworks = loopback))
            repeat(10) { assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login").status) }
        }

    @Test
    fun `forged leftmost X-Forwarded-For does not bypass the limit behind a trusted proxy`() =
        testApplication {
            setup(config(trustedProxies = loopback))
            // the proxy appended the real client (203.0.113.5) on the right, the client forged the left side
            repeat(3) { i ->
                val response = client.attempt("/api/v1/auth/login", forwardedFor = "198.51.100.$i, 203.0.113.5")
                assertEquals(HttpStatusCode.OK, response.status)
            }
            val blocked = client.attempt("/api/v1/auth/login", forwardedFor = "198.51.100.99, 203.0.113.5")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

            // a different real client behind the same proxy has its own budget
            val other = client.attempt("/api/v1/auth/login", forwardedFor = "198.51.100.99, 203.0.113.6")
            assertEquals(HttpStatusCode.OK, other.status)
        }

    @Test
    fun `X-Forwarded-For is ignored when the peer is not a trusted proxy`() =
        testApplication {
            setup(config())
            repeat(3) { i ->
                val response = client.attempt("/api/v1/auth/login", forwardedFor = "198.51.100.$i")
                assertEquals(HttpStatusCode.OK, response.status)
            }
            val blocked = client.attempt("/api/v1/auth/login", forwardedFor = "198.51.100.99")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        }

    @Test
    fun `disabled config does not limit`() =
        testApplication {
            setup(config(enabled = false))
            repeat(10) { assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login").status) }
        }

    @Test
    fun `idle limiters are evicted and active ones are kept`() {
        var now = 0L
        val limiters =
            ClientLimiters(
                idleTimeoutMs = 1_000,
                clock = { now },
                sweepThreshold = 2,
                sweepIntervalMs = 0,
            )
        val idle = limiters.get("10.0.0.1") { RateLimiter(1.0, 1) }
        val active = limiters.get("10.0.0.2") { RateLimiter(1.0, 1) }

        now = 900
        assertSame(active, limiters.get("10.0.0.2") { RateLimiter(1.0, 1) })

        // 10.0.0.1 has been idle for 1_500ms, 10.0.0.2 only for 600ms
        now = 1_500
        limiters.get("10.0.0.3") { RateLimiter(1.0, 1) }

        assertEquals(2, limiters.size)
        assertSame(active, limiters.get("10.0.0.2") { RateLimiter(1.0, 1) })
        assertNotSame(idle, limiters.get("10.0.0.1") { RateLimiter(1.0, 1) })
    }

    @Test
    fun `limiters are not swept below the threshold`() {
        var now = 0L
        val limiters = ClientLimiters(idleTimeoutMs = 1_000, clock = { now }, sweepThreshold = 100, sweepIntervalMs = 0)
        repeat(5) { limiters.get("10.0.0.$it") { RateLimiter(1.0, 1) } }
        now = 10_000
        limiters.get("10.0.0.99") { RateLimiter(1.0, 1) }
        assertEquals(6, limiters.size)
    }

    @Test
    fun `warns when private networks are trusted and no proxies are configured`() {
        assertTrue(shouldWarnAboutProxyMisconfiguration(RateLimitConfig()))
        assertTrue(shouldWarnAboutProxyMisconfiguration(config(trustedNetworks = listOf("192.168.0.0/16"))))
        assertTrue(shouldWarnAboutProxyMisconfiguration(config(trustedNetworks = listOf("::1/128"))))
        assertTrue(shouldWarnAboutProxyMisconfiguration(config(trustedNetworks = listOf("fd00::/8"))))
    }

    @Test
    fun `does not warn when proxies are set, networks are public, or limiting is disabled`() {
        assertFalse(shouldWarnAboutProxyMisconfiguration(config(trustedNetworks = loopback, trustedProxies = listOf("172.18.0.1/32"))))
        assertFalse(shouldWarnAboutProxyMisconfiguration(config(trustedNetworks = listOf("203.0.113.0/24"))))
        assertFalse(shouldWarnAboutProxyMisconfiguration(config()))
        assertFalse(shouldWarnAboutProxyMisconfiguration(config(enabled = false, trustedNetworks = loopback)))
    }

    @Test
    fun `ipv6 clients are keyed by their 64 prefix and ipv4 is unchanged`() {
        val a = limiterKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        assertEquals(a, limiterKey("2001:db8:1:2:1::1"))
        assertNotEquals(a, limiterKey("2001:db8:1:3:aaaa:bbbb:cccc:dddd"))
        assertEquals("203.0.113.5", limiterKey("203.0.113.5"))
        assertEquals("203.0.113.5", limiterKey("::ffff:203.0.113.5"))
        assertEquals("not-an-ip", limiterKey("not-an-ip"))
    }

    @Test
    fun `addresses in the same ipv6 64 share a limiter`() {
        val limiters = ClientLimiters(idleTimeoutMs = 1_000)
        val first = limiters.get("2001:db8:1:2::1") { RateLimiter(1.0, 1) }
        assertSame(first, limiters.get("2001:db8:1:2:ffff::9") { RateLimiter(1.0, 1) })
        assertNotSame(first, limiters.get("2001:db8:1:3::1") { RateLimiter(1.0, 1) })
        assertEquals(2, limiters.size)
    }

    @Test
    fun `rotating ipv6 addresses within a 64 does not bypass the limit`() =
        testApplication {
            setup(config(trustedProxies = loopback))
            repeat(3) { i ->
                assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "2001:db8:1:2::$i").status)
            }
            val blocked = client.attempt("/api/v1/auth/login", forwardedFor = "2001:db8:1:2:dead:beef::1")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        }

    @Test
    fun `entries are capped, new clients share an overflow limiter, and idle entries free space`() {
        var now = 0L
        val limiters =
            ClientLimiters(idleTimeoutMs = 1_000, clock = { now }, sweepThreshold = 1_000, maxEntries = 2)
        val a = limiters.get("10.0.0.1") { RateLimiter(1.0, 1) }
        val b = limiters.get("10.0.0.2") { RateLimiter(1.0, 1) }

        val c = limiters.get("10.0.0.3") { RateLimiter(1.0, 1) }
        val d = limiters.get("10.0.0.4") { RateLimiter(1.0, 1) }
        assertSame(c, d)
        assertNotSame(a, c)
        assertNotSame(b, c)
        assertEquals(2, limiters.size)
        // tracked clients keep their own limiter
        assertSame(a, limiters.get("10.0.0.1") { RateLimiter(1.0, 1) })

        // 10.0.0.2 goes idle, a new client sweeps it out and gets its own slot
        now = 1_500
        limiters.get("10.0.0.1") { RateLimiter(1.0, 1) }
        val e = limiters.get("10.0.0.5") { RateLimiter(1.0, 1) }
        assertNotSame(c, e)
        assertEquals(2, limiters.size)
    }

    @Test
    fun `untracked clients over the cap share one budget`() =
        testApplication {
            application {
                install(AuthRateLimiting) {
                    config = config(trustedProxies = loopback)
                    maxEntries = 1
                }
                routing { post("/api/v1/auth/login") { call.respondText("login") } }
            }
            // 203.0.113.1 takes the only slot, so .2 and .3 share the overflow limiter (3 attempts in total)
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.1").status)
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.2").status)
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.3").status)
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.2").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.3").status)
            // the tracked client is unaffected
            assertEquals(HttpStatusCode.OK, client.attempt("/api/v1/auth/login", forwardedFor = "203.0.113.1").status)
        }
}
