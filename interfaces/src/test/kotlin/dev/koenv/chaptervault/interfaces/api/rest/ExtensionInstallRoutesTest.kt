package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.infrastructure.extensions.ExtensionRegistryService
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExtensionInstallRoutesTest {
    private fun testApp(
        service: ExtensionRegistryService,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        if (cred.token == "admin-token") KtorPrincipal(UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN))) else null
                    }
                }
            }
            routing {
                authenticate("auth-bearer") {
                    extensionInstallRoutes(service)
                }
            }
        }
        block()
    }

    @Test
    fun `POST install returns 422 with ErrorResponse on failure`() {
        val service = mockk<ExtensionRegistryService>()
        coEvery { service.install(any()) } throws RuntimeException("extension not found in any registry")

        testApp(service) {
            val response =
                client.post("/extensions/install") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"extensionId":"some.ext"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"message\"")
            assertFalse(body.contains("\"extensionId\""), "should not echo request fields")
        }
    }
}
