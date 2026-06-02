package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.ExtensionConfigApi
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

class ExtensionConfigRoutesTest {
    private fun testApp(
        manager: ExtensionManager,
        configApi: ExtensionConfigApi = NoOpExtensionConfigApi(),
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
                    extensionConfigRoutes(manager, configApi)
                }
            }
        }
        block()
    }

    @Test
    fun `GET config for unknown extension returns 404 with error body`() {
        testApp(manager = emptyManager()) {
            val response = client.get("/extensions/unknown.ext/config") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
        }
    }

    @Test
    fun `GET config for known extension returns 200`() {
        val ext = fakeExtension("test.ext")
        testApp(manager = managerWith(ext)) {
            val response = client.get("/extensions/test.ext/config") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}

private fun emptyManager(): ExtensionManager = managerWith()

private fun managerWith(vararg extensions: Extension): ExtensionManager =
    object : ExtensionManager {
        private val entries = extensions.map { ExtensionEntry(it, ExtensionStatus.ENABLED, ExtensionSource.LOCAL) }

        override fun listAll() = entries

        override fun findById(id: String) = entries.find { it.extension.id == id }

        override fun enable(id: String) {}

        override fun disable(id: String) {}

        override fun reload(id: String) {}

        override fun unload(id: String) {}

        override fun install(
            extensionId: String,
            jarBytes: ByteArray,
        ) {}
    }

private fun fakeExtension(id: String): Extension =
    object : Extension {
        override val id = id
        override val name = id
        override val version = "1.0.0"

        override fun capabilities(): Set<Capability> = emptySet()

        override fun onEnable(context: ExtensionContext) {}

        override fun onDisable() {}
    }

private class NoOpExtensionConfigApi : ExtensionConfigApi {
    override suspend fun getAll(extensionId: String): Map<String, String> = emptyMap()

    override suspend fun setAll(
        extensionId: String,
        values: Map<String, String>,
    ) {}
}
