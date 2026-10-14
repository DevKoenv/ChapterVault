package dev.koenv.chaptervault.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebHardeningTest {
    private fun ApplicationTestBuilder.setup(origins: List<String>) {
        application {
            configureCors(origins)
            install(DefaultSecurityHeaders)
            routing { get("/api/v1/thing") { call.respondText("ok") } }
        }
    }

    private suspend fun ApplicationTestBuilder.preflight(origin: String) =
        client.options("/api/v1/thing") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }

    @Test
    fun `without configured origins no other site may call the API from a browser`() =
        testApplication {
            setup(emptyList())

            val response = preflight("https://evil.example")

            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            val simple = client.get("/api/v1/thing") { header(HttpHeaders.Origin, "https://evil.example") }
            assertNull(simple.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(HttpStatusCode.OK, simple.status)
        }

    @Test
    fun `a configured origin is allowed and others are not`() =
        testApplication {
            setup(listOf("http://192.168.1.10:3000"))

            assertEquals("http://192.168.1.10:3000", preflight("http://192.168.1.10:3000").headers[HttpHeaders.AccessControlAllowOrigin])
            assertNull(preflight("https://evil.example").headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `responses tell browsers not to guess types, frame the page or leak the address`() =
        testApplication {
            setup(emptyList())

            val headers = client.get("/api/v1/thing").headers

            assertEquals("nosniff", headers["X-Content-Type-Options"])
            assertEquals("no-referrer", headers[HttpHeaders.Referrer])
            assertEquals("DENY", headers["X-Frame-Options"])
        }

    @Test
    fun `error responses carry the headers too`() =
        testApplication {
            setup(emptyList())

            assertNotNull(client.get("/missing").headers["X-Content-Type-Options"])
        }
}
