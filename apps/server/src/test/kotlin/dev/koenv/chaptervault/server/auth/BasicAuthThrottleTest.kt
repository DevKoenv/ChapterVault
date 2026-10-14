package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.infrastructure.config.EndpointLimitConfig
import dev.koenv.chaptervault.infrastructure.config.RateLimitConfig
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.server.plugins.AuthRateLimiting
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BasicAuthThrottleTest {
    private class CountingAuth : AuthApi {
        val validations = AtomicInteger()
        private val principal = UserPrincipal(Id.generate(), "alice", setOf(Role.USER))

        override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> {
            validations.incrementAndGet()
            return if (credentials.password == RIGHT) Result.Success(principal) else Result.Failure(AppError.Unauthorized())
        }

        override suspend fun register(
            credentials: Credentials,
            role: Role,
        ): Result<UserPrincipal> = error("unused")

        override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> = error("unused")

        override suspend fun validateSession(token: String): Result<UserPrincipal> = error("unused")

        override suspend fun invalidateSession(token: String): Result<Unit> = error("unused")

        override suspend fun listUsers(): Result<List<UserPrincipal>> = error("unused")

        override suspend fun deleteUser(id: Id): Result<Unit> = error("unused")

        override suspend fun updateUserRole(
            id: Id,
            role: Role,
        ): Result<UserPrincipal> = error("unused")

        override suspend fun changePassword(
            userId: Id,
            currentPassword: String,
            newPassword: String,
        ): Result<Unit> = error("unused")

        override suspend fun cleanupExpiredSessions(): Result<Unit> = error("unused")
    }

    // the test engine's peer is the loopback address, so it acts as the trusted reverse proxy and X-Forwarded-For
    // selects the client address
    private fun config(
        enabled: Boolean = true,
        trustedNetworks: List<String> = emptyList(),
    ) = RateLimitConfig(
        enabled = enabled,
        trustedNetworks = trustedNetworks,
        trustedProxies = listOf("127.0.0.0/8"),
        login = EndpointLimitConfig(maxAttempts = 3, windowMinutes = 60),
    )

    private fun ApplicationTestBuilder.setup(
        auth: AuthApi,
        rateLimit: RateLimitConfig = config(),
    ) {
        application {
            install(AuthRateLimiting) { config = rateLimit }
            configureAuthentication(auth)
            routing {
                authenticate("auth-basic") { get("/opds/v1/catalog") { call.respondText("ok") } }
            }
        }
    }

    private suspend fun HttpClient.opds(
        user: String = "alice",
        password: String = "wrong",
        client: String = "203.0.113.1",
    ): HttpResponse =
        get("/opds/v1/catalog") {
            basicAuth(user, password)
            header("X-Forwarded-For", client)
        }

    @Test
    fun `failed attempts over the limit get 429 with Retry-After before the password is checked`() =
        testApplication {
            val auth = CountingAuth()
            setup(auth)
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.opds().status) }

            val blocked = client.opds()

            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertTrue(assertNotNull(blocked.headers[HttpHeaders.RetryAfter]).toLong() > 0)
            assertEquals(3, auth.validations.get(), "a blocked client must not cost a password check")
        }

    @Test
    fun `the right password is refused while the client is blocked`() =
        testApplication {
            val auth = CountingAuth()
            setup(auth)
            repeat(3) { client.opds() }

            assertEquals(HttpStatusCode.TooManyRequests, client.opds(password = RIGHT).status)
            assertEquals(3, auth.validations.get())
        }

    @Test
    fun `successful requests do not use up the budget`() =
        testApplication {
            setup(CountingAuth())
            repeat(10) { assertEquals(HttpStatusCode.OK, client.opds(password = RIGHT).status) }
        }

    @Test
    fun `one blocked client does not block another`() =
        testApplication {
            setup(CountingAuth())
            repeat(4) { client.opds(user = "alice", client = "203.0.113.1") }

            assertEquals(HttpStatusCode.OK, client.opds(user = "bob", password = RIGHT, client = "203.0.113.2").status)
        }

    @Test
    fun `failures spread over many addresses still stop guessing a single account`() =
        testApplication {
            val auth = CountingAuth()
            setup(auth)
            // the per-username budget is five times the per-client one: 15 failures
            repeat(15) { assertEquals(HttpStatusCode.Unauthorized, client.opds(client = "198.51.100.$it").status) }

            val blocked = client.opds(password = RIGHT, client = "198.51.100.200")

            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals(15, auth.validations.get())
        }

    @Test
    fun `trusted networks are not throttled`() =
        testApplication {
            setup(CountingAuth(), config(trustedNetworks = listOf("203.0.113.0/24")))
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.opds().status) }
        }

    @Test
    fun `nothing is throttled when rate limiting is disabled`() =
        testApplication {
            setup(CountingAuth(), config(enabled = false))
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.opds().status) }
        }

    @Test
    fun `requests without credentials do not count as failures`() =
        testApplication {
            setup(CountingAuth())
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1/catalog").status) }

            assertEquals(HttpStatusCode.OK, client.opds(password = RIGHT).status)
        }

    private companion object {
        const val RIGHT = "right-password"
    }
}
