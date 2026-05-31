package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.extensions.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.extensions.loader.ExternalExtensionLoader
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ExtensionRoutesTest {
    @TempDir
    lateinit var tempDir: Path

    private val testExtension =
        object : Extension {
            override val id = "test.extension"
            override val name = "Test Extension"
            override val version = "1.0.0"

            override fun capabilities(): Set<Capability> = setOf(Capability.CanFetchSeries, Capability.CanDownloadChapters)

            override fun onEnable(context: ExtensionContext) {}

            override fun onDisable() {}
        }

    private fun makeLoaderService(): ExtensionLoaderService {
        val extRegistry = DefaultExtensionRegistry()
        val connRegistry = DefaultConnectorRegistry()
        return ExtensionLoaderService(
            extensionRegistry = extRegistry,
            connectorRegistryDelegate = connRegistry,
            contextFactory = { _ ->
                object : ExtensionContext {
                    override val httpClient get() = error("not needed in test")
                    override val library get() = error("not needed in test")
                    override val progress get() = error("not needed in test")
                    override val system get() = error("not needed in test")
                    override val connectorRegistry = connRegistry
                    override val dataDir = tempDir

                    override fun rateLimiter(
                        bucket: String,
                        requestsPerSecond: Double,
                    ) = error("not needed in test")

                    override fun logger(name: String) = org.slf4j.LoggerFactory.getLogger(name)
                }
            },
            externalLoader = ExternalExtensionLoader(extensionsDir = tempDir, serverVersion = "1.0.0"),
            bundledExtensions = listOf(testExtension),
        )
    }

    private fun testApp(
        loaderService: ExtensionLoaderService,
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
                    extensionRoutes(loaderService)
                }
            }
        }
        block()
    }

    @Test
    fun `GET extensions returns 200 with all extensions`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            val response = client.get("/extensions") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "test.extension")
        }
    }

    @Test
    fun `GET extensions by id returns 200 with ENABLED status`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            val response = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "test.extension")
            assertContains(body, ExtensionStatus.ENABLED.name)
        }
    }

    @Test
    fun `GET extensions with unknown id returns 404`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            val response = client.get("/extensions/unknown.ext") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `POST extensions disable returns 204 and sets DISABLED`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            val response = client.post("/extensions/test.extension/disable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val detail = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertContains(detail.bodyAsText(), ExtensionStatus.DISABLED.name)
        }
    }

    @Test
    fun `POST extensions enable after disable returns 204 and sets ENABLED`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            client.post("/extensions/test.extension/disable") { bearerAuth("admin-token") }
            val response = client.post("/extensions/test.extension/enable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val detail = client.get("/extensions/test.extension") { bearerAuth("admin-token") }
            assertContains(detail.bodyAsText(), ExtensionStatus.ENABLED.name)
        }
    }

    @Test
    fun `POST extensions enable with unknown id returns 404`() {
        val loaderService = makeLoaderService()
        loaderService.loadAll()
        testApp(loaderService) {
            val response = client.post("/extensions/unknown.ext/enable") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}
