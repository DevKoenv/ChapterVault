package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.infrastructure.connectors.MockConnector
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
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

class ConnectorRoutesTest {
    private val registry: ConnectorRegistry = DefaultConnectorRegistry().also { it.register(MockConnector()) }

    private fun stubLibrary(inLibraryIds: Set<String> = emptySet()): LibraryReadApi =
        object : LibraryReadApi {
            override suspend fun getSeries(id: Id): Result<Series> = error("not used")

            override suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> = error("not used")

            override suspend fun searchLibrary(
                query: String,
                request: PageRequest,
            ): Result<Pagination<Series>> = error("not used")

            override suspend fun getChapter(id: Id): Result<Chapter> = error("not used")

            override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> = error("not used")

            override suspend fun listChaptersByStatus(
                seriesId: Id,
                status: DownloadStatus,
            ): Result<List<Chapter>> = error("not used")

            override suspend fun inLibraryExternalIds(
                connectorId: String,
                externalIds: List<String>,
            ): Result<Set<String>> = Result.Success(inLibraryIds.intersect(externalIds.toSet()))
        }

    private fun testApp(
        registry: ConnectorRegistry,
        libraryRead: LibraryReadApi = stubLibrary(),
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
                    connectorRoutes(registry, libraryRead)
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
    fun `GET connectors search marks inLibrary true for known externalId`() {
        testApp(registry, stubLibrary(inLibraryIds = setOf("mock-one-piece"))) {
            val response = client.get("/connectors/mock/search?q=piece") { bearerAuth("admin-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // mock-one-piece (One Piece) should be inLibrary:true, mock-naruto should be false
            assertContains(body, "\"externalId\":\"mock-one-piece\"")
            assertContains(body, "\"inLibrary\":true")
            assertContains(body, "\"inLibrary\":false")
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
