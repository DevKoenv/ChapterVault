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
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
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
        DatabaseFactory.close()
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
    fun `the routes the server serves are exactly the routes the OpenAPI document describes`() {
        lateinit var served: Set<String>
        testApplication {
            lateinit var app: Application
            application {
                bootstrap()
                app = this
            }
            startApplication()
            served =
                app
                    .plugin(RoutingRoot)
                    .getAllRoutes()
                    .mapNotNull { it.methodAndPath() }
                    .filterNot { it.substringAfter(' ').startsWith("/swagger") } // the UI itself, not part of the API
                    .toSet()
        }
        val documented = documentedRoutes()
        // guards against both sides being empty, which would make the comparison pass trivially
        for (route in listOf("GET /health", "POST /api/v1/auth/login", "DELETE /api/v1/users/{id}", "GET /opds/v1/search.xml")) {
            assertTrue(route in served, "$route is not among the ${served.size} served routes")
            assertTrue(route in documented, "$route is not among the ${documented.size} documented routes")
        }

        assertEquals(emptySet(), documented - served, "documented in openapi/documentation.yaml but not served")
        assertEquals(emptySet(), served - documented, "served but missing from openapi/documentation.yaml")
    }

    // "GET /api/v1/users/{id}" for a route that ends in a method; authentication and other non-path selectors are skipped
    private fun RoutingNode.methodAndPath(): String? {
        val method = (selector as? HttpMethodRouteSelector)?.method?.value ?: return null
        val segments =
            generateSequence(parent) { it.parent }.toList().asReversed().mapNotNull { node ->
                when (val s = node.selector) {
                    is PathSegmentConstantRouteSelector -> s.value
                    is PathSegmentParameterRouteSelector -> "{${s.name}}"
                    else -> null
                }
            }
        return "$method /${segments.joinToString("/")}"
    }

    // the paths section: a path item with its own `servers:` is served from the root, all others under /api/v1
    private fun documentedRoutes(): Set<String> {
        val lines =
            checkNotNull(javaClass.getResourceAsStream("/openapi/documentation.yaml")) { "openapi/documentation.yaml is missing" }
                .use { it.readBytes().decodeToString() }
                .lines()
        val start = lines.indexOf("paths:")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].startsWith(" ") } ?: lines.size
        val routes = mutableSetOf<String>()
        var path: String? = null
        var fromRoot = false
        val methods = mutableListOf<String>()

        fun flush() {
            path?.let { p -> methods.forEach { routes += "$it ${if (fromRoot) p else "/api/v1$p"}" } }
            methods.clear()
            fromRoot = false
        }
        for (line in lines.subList(start + 1, end)) {
            val pathItem = Regex("""^ {2}(/\S*):\s*$""").matchEntire(line)
            val operation = Regex("""^ {4}(get|post|put|patch|delete):\s*$""").matchEntire(line)
            when {
                pathItem != null -> {
                    flush()
                    path = pathItem.groupValues[1]
                }
                line.trimEnd() == "    servers:" -> fromRoot = true
                operation != null -> methods += operation.groupValues[1].uppercase()
            }
        }
        flush()
        return routes
    }

    @Test
    fun `responses carry the security headers`() =
        serverTest {
            val response = client.get("/health")

            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
}
