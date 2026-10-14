package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import dev.koenv.chaptervault.infrastructure.config.UpdateConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.server.auth.AdminBootstrap
import dev.koenv.chaptervault.server.config.ConfigValidator
import dev.koenv.chaptervault.server.di.allModules
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Starts the whole server the way [main] does (real Koin modules, real database, real plugins and routes) and talks to it over
 * HTTP, so a missing binding, a wrong plugin order or a route that was never registered fails here and not on a user's machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapIntegrationTest {
    private val announcements = mutableListOf<String>()
    private lateinit var adminPassword: String

    // not @TempDir: on Windows the open SQLite file would make JUnit's own cleanup fail
    private val dir: Path = Files.createTempDirectory("chaptervault-bootstrap")

    @BeforeAll
    fun startServices() {
        val config: AppConfig =
            ConfigLoader
                .load(env = { if (it == "CHAPTERVAULT_DATA_DIR") dir.toString() else null })
                .let { it.copy(updates = UpdateConfig(checkEnabled = false)) }
        startKoin { modules(allModules + module { single<AppConfig> { config } }) }
        // the same steps as DependencyInjection.start(), minus the config that is read from the real environment
        ConfigValidator.validate(config)
        DatabaseFactory.init(config.database)
        GlobalContext.get().get<ExtensionLoaderService>().loadAll()
        announcements.clear()
        runBlocking { AdminBootstrap(GlobalContext.get().get<AuthApi>(), env = { null }, announce = { announcements += it }).run() }
        adminPassword =
            announcements
                .single()
                .lines()
                .first { "password" in it.lowercase() }
                .substringAfterLast(' ')
    }

    @AfterAll
    fun stopServices() {
        stopKoin()
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun serverTest(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { bootstrap() }
            block()
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(): String {
        val response =
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"$adminPassword"}""")
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    @Test
    fun `health reports the database and the executor as ok`() =
        serverTest {
            // the executor reports itself alive after its first heartbeat, which is a moment after the start
            var response = client.get("/health")
            repeat(20) {
                if (response.status != HttpStatusCode.OK) {
                    delay(250)
                    response = client.get("/health")
                }
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue("\"ok\"" in response.bodyAsText())
        }

    @Test
    fun `the api refuses requests without a token and accepts the generated admin`() =
        serverTest {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/library/series").status)

            val token = login()
            for (path in listOf("/api/v1/library/series", "/api/v1/tasks", "/api/v1/connectors", "/api/v1/extensions")) {
                val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
            }
        }

    @Test
    fun `the bundled connectors are registered`() =
        serverTest {
            val token = login()

            val body = client.get("/api/v1/connectors") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()

            assertTrue("mangadex" in body.lowercase(), body)
        }

    @Test
    fun `opds needs basic auth and serves the root catalog to the admin`() =
        serverTest {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/opds/v1").status)

            val basic = Base64.getEncoder().encodeToString("admin:$adminPassword".toByteArray())
            val response = client.get("/opds/v1") { header(HttpHeaders.Authorization, "Basic $basic") }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }

    @Test
    fun `responses carry the security headers`() =
        serverTest {
            val response = client.get("/health")

            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
}
