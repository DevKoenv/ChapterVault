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
import io.ktor.client.request.post
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionReloadRoutesTest {
    private val testExtension =
        object : Extension {
            override val id = "test.extension"
            override val name = "Test Extension"
            override val version = "1.0.0"

            override fun capabilities(): Set<Capability> = emptySet()

            override fun onEnable(context: ExtensionContext) {}

            override fun onDisable() {}
        }

    private fun makeManager(): TrackingFakeExtensionManager {
        val entry =
            ExtensionEntry(
                extension = testExtension,
                status = ExtensionStatus.ENABLED,
                source = ExtensionSource.LOCAL,
            )
        return TrackingFakeExtensionManager(mutableListOf(entry))
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
                            "user-token" -> KtorPrincipal(UserPrincipal(Id.generate(), "user", setOf(Role.USER)))
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    extensionReloadRoutes(manager)
                }
            }
        }
        block()
    }

    @Test
    fun `POST reload without auth returns 401`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/test.extension/reload")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST reload with non-admin auth returns 403`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/test.extension/reload") { bearerAuth("user-token") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `POST reload with admin auth and unknown id returns 404`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/unknown.ext/reload") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST reload with admin auth and known id returns 204`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/test.extension/reload") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(manager.reloadCalled)
        }
    }

    @Test
    fun `POST unload with admin auth and known id returns 204`() {
        val manager = makeManager()
        testApp(manager) {
            val response = client.post("/extensions/test.extension/unload") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(manager.unloadCalled)
        }
    }
}

private class TrackingFakeExtensionManager(
    private val entries: MutableList<ExtensionEntry>,
) : ExtensionManager {
    var reloadCalled = false
    var unloadCalled = false

    override fun listAll(): List<ExtensionEntry> = entries.toList()

    override fun findById(id: String): ExtensionEntry? = entries.find { it.extension.id == id }

    override fun enable(id: String) {
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = ExtensionStatus.ENABLED)
    }

    override fun disable(id: String) {
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = ExtensionStatus.DISABLED)
    }

    override fun unload(id: String) {
        unloadCalled = true
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = ExtensionStatus.UNLOADED)
    }

    override fun reload(id: String) {
        reloadCalled = true
        val index = entries.indexOfFirst { it.extension.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = ExtensionStatus.ENABLED)
    }
}
