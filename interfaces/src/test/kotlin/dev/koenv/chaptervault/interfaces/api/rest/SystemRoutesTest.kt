package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.UpdateInfo
import dev.koenv.chaptervault.kernel.api.UpdateInfoApi
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
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

class SystemRoutesTest {
    private val updates =
        object : UpdateInfoApi {
            override fun getUpdateInfo() = UpdateInfo("1.0.0", "1.2.0", "https://example.org/release", true)
        }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
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
                routing { authenticate("auth-bearer") { systemRoutes(updates) } }
            }
            block()
        }

    @Test
    fun `GET system-updates reports the update status to an admin`() =
        testApp {
            val response = client.get("/system/updates") { bearerAuth("admin-token") }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"currentVersion\":\"1.0.0\"")
            assertContains(body, "\"latestVersion\":\"1.2.0\"")
            assertContains(body, "\"updateAvailable\":true")
            assertContains(body, "https://example.org/release")
        }

    @Test
    fun `GET system-updates is not for ordinary users`() =
        testApp {
            assertEquals(HttpStatusCode.Forbidden, client.get("/system/updates") { bearerAuth("user-token") }.status)
        }

    @Test
    fun `GET system-updates needs a token`() =
        testApp {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/system/updates").status)
        }
}
