package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.infrastructure.config.EndpointLimitConfig
import dev.koenv.chaptervault.infrastructure.config.RateLimitConfig
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
