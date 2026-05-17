package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthRoutesTest {

    private val fakeSession = Session(
        id = Id.generate(),
        userId = Id.generate(),
        token = "test-token-abc",
        expiresAt = Instant.now().plusSeconds(3600),
    )
    private val fakePrincipal = UserPrincipal(
        id = fakeSession.userId,
        username = "alice",
        roles = setOf(Role.USER),
    )

    private val happyAuth = object : AuthApi {
        override suspend fun register(credentials: Credentials, role: Role) =
            Result.Success(fakePrincipal)
        override suspend fun authenticate(credentials: Credentials) =
            Result.Success(fakePrincipal to fakeSession)
        override suspend fun validateSession(token: String) = Result.Success(fakePrincipal)
        override suspend fun invalidateSession(token: String) = Result.Success(Unit)
    }

    private val failAuth = object : AuthApi {
        override suspend fun register(credentials: Credentials, role: Role) =
            Result.Failure(AppError.Conflict("Username taken"))
        override suspend fun authenticate(credentials: Credentials) =
            Result.Failure(AppError.Unauthorized())
        override suspend fun validateSession(token: String) =
            Result.Failure(AppError.Unauthorized())
        override suspend fun invalidateSession(token: String) = Result.Success(Unit)
    }

    private fun ApplicationTestBuilder.install(auth: AuthApi) {
        application {
            install(ContentNegotiation) { json() }
            authRoutes(auth)
        }
    }

    @Test
    fun `POST auth-login returns 200 with token on success`() {
        testApplication {
            install(happyAuth)
            val response = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pass"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("test-token-abc", body["token"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `POST auth-login returns 401 on bad credentials`() {
        testApplication {
            install(failAuth)
            val response = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"wrong"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST auth-logout returns 204`() {
        testApplication {
            install(happyAuth)
            val response = client.post("/auth/logout") {
                header(HttpHeaders.Authorization, "Bearer test-token-abc")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `POST auth-register returns 201 with username`() {
        testApplication {
            install(happyAuth)
            val response = client.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pass"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("alice", body["username"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `POST auth-register returns 409 when username taken`() {
        testApplication {
            install(failAuth)
            val response = client.post("/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pass"}""")
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}
