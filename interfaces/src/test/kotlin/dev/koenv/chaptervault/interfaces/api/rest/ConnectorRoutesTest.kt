package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.sources.MockConnector
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class ConnectorRoutesTest {
    private val registry: ConnectorRegistry = DefaultConnectorRegistry().also { it.register(MockConnector()) }

    private fun testApp(
        registry: ConnectorRegistry,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        when (cred.token) {
                            "admin-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN)))
                            "user-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    connectorRoutes(registry)
                }
            }
        }
        block()
    }

    @Test
    fun `GET connectors returns 200 with mock connector listed`() {
        testApp(registry) {
            val response = client.get("/connectors") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "mock")
            assertContains(body, "Mock Connector")
        }
    }

    @Test
    fun `GET connectors search returns 200 with 2 results for piece query`() {
        testApp(registry) {
            val response = client.get("/connectors/mock/search?q=piece") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "One Piece")
            assertContains(body, "totalItems")
        }
    }

    @Test
    fun `GET connectors search returns 404 for missing connector`() {
        testApp(registry) {
            val response = client.get("/connectors/missing/search?q=piece") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `GET connectors series returns 200 with metadata`() {
        testApp(registry) {
            val response = client.get("/connectors/mock/series/mock-001") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "mock-001")
        }
    }

    @Test
    fun `GET connectors series chapters returns 200 with 3 chapters`() {
        testApp(registry) {
            val response = client.get("/connectors/mock/series/mock-001/chapters") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "Chapter 1")
            assertContains(body, "Chapter 2")
            assertContains(body, "Chapter 3")
        }
    }

    @Test
    fun `GET connectors returns 403 for USER token`() {
        testApp(registry) {
            val response = client.get("/connectors") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }
}
