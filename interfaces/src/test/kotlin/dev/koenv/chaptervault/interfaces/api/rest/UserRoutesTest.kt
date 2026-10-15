package dev.koenv.chaptervault.interfaces.api.rest

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserRoutesTest {
    private val adminId = Id.from("00000000-0000-0000-0000-0000000000a1")
    private val userId = Id.from("00000000-0000-0000-0000-0000000000b2")
    private val admin = UserPrincipal(adminId, "root", setOf(Role.ADMIN))
    private val user = UserPrincipal(userId, "alice", setOf(Role.USER))

    private class RecordingAuth(
        private val principals: List<UserPrincipal>,
    ) : AuthApi {
        val deleted = mutableListOf<Id>()
        val roleChanges = mutableListOf<Pair<Id, Role>>()
        val passwordChanges = mutableListOf<Triple<Id, String, String>>()
        var deleteResult: Result<Unit> = Result.Success(Unit)
        var roleResult: Result<UserPrincipal>? = null
        val registered = mutableListOf<Pair<Credentials, Role>>()
        var registerResult: Result<UserPrincipal>? = null
        val passwordResets = mutableListOf<Pair<Id, String>>()
        var setPasswordResult: Result<Unit> = Result.Success(Unit)

        override suspend fun register(
            credentials: Credentials,
            role: Role,
        ): Result<UserPrincipal> {
            registered += credentials to role
            return registerResult ?: Result.Success(UserPrincipal(Id.generate(), credentials.username, setOf(role)))
        }

        override suspend fun setPassword(
            id: Id,
            newPassword: String,
        ): Result<Unit> {
            passwordResets += id to newPassword
            return setPasswordResult
        }

        override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> = error("not used")

        override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> = error("not used")

        override suspend fun validateSession(token: String): Result<UserPrincipal> = error("not used")

        override suspend fun invalidateSession(token: String): Result<Unit> = Result.Success(Unit)

        override suspend fun listUsers(): Result<List<UserPrincipal>> = Result.Success(principals)

        override suspend fun deleteUser(id: Id): Result<Unit> {
            deleted += id
            return deleteResult
        }

        override suspend fun updateUserRole(
            id: Id,
            role: Role,
        ): Result<UserPrincipal> {
            roleChanges += id to role
            return roleResult ?: Result.Success(principals.first { it.id == id }.copy(roles = setOf(role)))
        }

        override suspend fun changePassword(
            userId: Id,
            currentPassword: String,
            newPassword: String,
        ): Result<Unit> {
            passwordChanges += Triple(userId, currentPassword, newPassword)
            return Result.Success(Unit)
        }

        override suspend fun cleanupExpiredSessions(): Result<Unit> = Result.Success(Unit)
    }

    private fun testApp(
        auth: RecordingAuth,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("auth-bearer") {
                    authenticate { cred ->
                        when (cred.token) {
                            "admin-token" -> KtorPrincipal(admin)
                            "user-token" -> KtorPrincipal(user)
                            else -> null
                        }
                    }
                }
            }
            routing { authenticate("auth-bearer") { userRoutes(auth) } }
        }
        block()
    }

    @Test
    fun `GET auth-me returns the caller`() {
        testApp(RecordingAuth(listOf(admin, user))) {
            val response = client.get("/auth/me") { bearerAuth("user-token") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "alice")
        }
    }

    @Test
    fun `change-password rejects a short new password without calling the service`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response =
                client.post("/auth/change-password") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"old-password","newPassword":"short"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(auth.passwordChanges.isEmpty())
        }
    }

    @Test
    fun `change-password passes the caller's id to the service`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response =
                client.post("/auth/change-password") {
                    bearerAuth("user-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"old-password","newPassword":"a-better-password"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(listOf(Triple(userId, "old-password", "a-better-password")), auth.passwordChanges)
        }
    }

    @Test
    fun `GET users is admin only`() {
        testApp(RecordingAuth(listOf(admin, user))) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/users") { bearerAuth("user-token") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/users") { bearerAuth("admin-token") }.status)
        }
    }

    @Test
    fun `DELETE users removes another user`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = client.delete("/users/$userId") { bearerAuth("admin-token") }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(listOf(userId), auth.deleted)
        }
    }

    @Test
    fun `DELETE users refuses to delete yourself`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = client.delete("/users/$adminId") { bearerAuth("admin-token") }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(auth.deleted.isEmpty())
        }
    }

    @Test
    fun `DELETE users is forbidden for non-admins`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = client.delete("/users/$adminId") { bearerAuth("user-token") }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(auth.deleted.isEmpty())
        }
    }

    @Test
    fun `DELETE users rejects a malformed id`() {
        testApp(RecordingAuth(listOf(admin, user))) {
            assertEquals(HttpStatusCode.BadRequest, client.delete("/users/nope") { bearerAuth("admin-token") }.status)
        }
    }

    @Test
    fun `DELETE users maps a last-admin conflict to 409`() {
        val auth = RecordingAuth(listOf(admin, user))
        auth.deleteResult = Result.Failure(AppError.Conflict("Cannot remove the last administrator"))
        testApp(auth) {
            val response = client.delete("/users/$userId") { bearerAuth("admin-token") }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        token: String,
        body: String,
    ) = client.post(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun field(
        body: String,
        name: String,
    ): String? = (Json.parseToJsonElement(body).jsonObject[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    @Test
    fun `POST users creates a user with the given password and role`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = postJson("/users", "admin-token", """{"username":"bob","password":"bobs-password","role":"guest"}""")

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertEquals("bob", field(body, "username"))
            assertContains(body, "GUEST")
            assertEquals(null, field(body, "generatedPassword"))
            assertFalse("bobs-password" in body, "a chosen password is never echoed")
        }
        assertEquals(listOf(Credentials("bob", "bobs-password") to Role.GUEST), auth.registered)
    }

    @Test
    fun `POST users without a password generates one and returns it once`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = postJson("/users", "admin-token", """{"username":"bob"}""")

            assertEquals(HttpStatusCode.Created, response.status)
            val generated = assertNotNull(field(response.bodyAsText(), "generatedPassword"))
            assertEquals(24, generated.length)
            assertEquals(listOf(Credentials("bob", generated) to Role.USER), auth.registered)
        }
    }

    @Test
    fun `POST users rejects a short password and an unknown role, and is for admins only`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            assertEquals(HttpStatusCode.BadRequest, postJson("/users", "admin-token", """{"username":"bob","password":"short"}""").status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/users", "admin-token", """{"username":"bob","role":"king"}""").status)
            assertEquals(HttpStatusCode.Forbidden, postJson("/users", "user-token", """{"username":"bob"}""").status)
        }
        assertTrue(auth.registered.isEmpty())
    }

    @Test
    fun `POST users maps a taken username to 409`() {
        val auth = RecordingAuth(listOf(admin, user))
        auth.registerResult = Result.Failure(AppError.Conflict("Username 'alice' already taken"))
        testApp(auth) {
            assertEquals(HttpStatusCode.Conflict, postJson("/users", "admin-token", """{"username":"alice"}""").status)
        }
    }

    @Test
    fun `POST users-id-password sets the given password`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = postJson("/users/$userId/password", "admin-token", """{"password":"a-new-password"}""")

            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        assertEquals(listOf(userId to "a-new-password"), auth.passwordResets)
    }

    @Test
    fun `POST users-id-password without a password generates one and returns it`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response = postJson("/users/$userId/password", "admin-token", "{}")

            assertEquals(HttpStatusCode.OK, response.status)
            val generated = assertNotNull(field(response.bodyAsText(), "generatedPassword"))
            assertEquals(listOf(userId to generated), auth.passwordResets)
        }
    }

    @Test
    fun `POST users-id-password refuses your own account, a short password and non-admins`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            assertEquals(HttpStatusCode.BadRequest, postJson("/users/$adminId/password", "admin-token", "{}").status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/users/$userId/password", "admin-token", """{"password":"short"}""").status)
            assertEquals(HttpStatusCode.Forbidden, postJson("/users/$adminId/password", "user-token", "{}").status)
        }
        assertTrue(auth.passwordResets.isEmpty())
    }

    @Test
    fun `POST users-id-password maps an unknown user to 404`() {
        val auth = RecordingAuth(listOf(admin, user))
        auth.setPasswordResult = Result.Failure(AppError.NotFound("User", "x"))
        testApp(auth) {
            assertEquals(HttpStatusCode.NotFound, postJson("/users/${Id.generate()}/password", "admin-token", "{}").status)
        }
    }

    @Test
    fun `PATCH users role changes the role`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response =
                client.patch("/users/$userId/role") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"guest"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(userId to Role.GUEST), auth.roleChanges)
        }
    }

    @Test
    fun `PATCH users role rejects an unknown role`() {
        val auth = RecordingAuth(listOf(admin, user))
        testApp(auth) {
            val response =
                client.patch("/users/$userId/role") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"superuser"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(auth.roleChanges.isEmpty())
        }
    }

    @Test
    fun `PATCH users role maps a last-admin conflict to 409`() {
        val auth = RecordingAuth(listOf(admin, user))
        auth.roleResult = Result.Failure(AppError.Conflict("Cannot remove the last administrator"))
        testApp(auth) {
            val response =
                client.patch("/users/$adminId/role") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"USER"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}
