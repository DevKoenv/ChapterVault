package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionEntry
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.extension.ExtensionStatus
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ExtensionRoutesTest {
    private val testExtension =
        object : Extension {
            override val id = "test.extension"
            override val name = "Test Extension"
            override val version = "1.0.0"

            override fun capabilities(): Set<Capability> = setOf(Capability.CanFetchSeries, Capability.CanDownloadChapters)

            override fun onEnable(context: ExtensionContext) {}

            override fun onDisable() {}
        }

    private fun makeManager(): FakeExtensionManager {
        val entry =
            ExtensionEntry(
                extension = testExtension,
                status = ExtensionStatus.ENABLED,
                source = ExtensionSource.BUNDLED,
            )
        return FakeExtensionManager(mutableListOf(entry))
    }

    private fun testApp(
        manager: ExtensionManager,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        when (cred.token) {
                            "admin-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN)))
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    extensionRoutes(manager)
                }
            }
        }
        block()
    }

    @Test
    fun `GET extensions returns 200 with all extensions`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.get("/extensions") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "test.extension")
        }
    }

    @Test
    fun `GET extensions by id returns 200 with ENABLED status`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "test.extension")
            assertContains(body, ExtensionStatus.ENABLED.name)
        }
    }

    @Test
    fun `GET extensions with unknown id returns 404`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.get("/extensions/unknown.ext") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST extensions disable returns 204 and sets DISABLED`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/test.extension/disable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val detail = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertContains(detail.bodyAsText(), ExtensionStatus.DISABLED.name)
        }
    }

    @Test
    fun `POST extensions enable after disable returns 204 and sets ENABLED`() {
        val manager = makeManager()
        testApp(manager) {
            client.post("/extensions/test.extension/disable") { bearerAuth("admin-token") }
            val response = client.post("/extensions/test.extension/enable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val detail = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertContains(detail.bodyAsText(), ExtensionStatus.ENABLED.name)
        }
    }

    @Test
    fun `POST extensions enable with unknown id returns 404`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/unknown.ext/enable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}

private class FakeExtensionManager(
    private val entries: MutableList<ExtensionEntry>,
) : ExtensionManager {
    override fun listAll(): List<ExtensionEntry> = entries.toList()

    override fun findById(id: String): ExtensionEntry? = entries.find { it.extension.id == id }

    override fun enable(id: String) {
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) {
            entries[index] = entries[index].copy(status = ExtensionStatus.ENABLED)
        }
    }

    override fun disable(id: String) {
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) {
            entries[index] = entries[index].copy(status = ExtensionStatus.DISABLED)
        }
    }
}
