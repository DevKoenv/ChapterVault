package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CachingAuthApiTest {
    /** Users live in a map; [lookups] counts how often the (expensive) password check ran. */
    private class FakeAuth : AuthApi {
        class Account(
            var principal: UserPrincipal,
            var password: String,
        )

        val accounts = mutableMapOf<String, Account>()
        var lookups = 0
        var failNextMutation = false

        fun add(
            name: String,
            password: String,
            role: Role = Role.USER,
        ): UserPrincipal = UserPrincipal(Id.generate(), name, setOf(role)).also { accounts[name] = Account(it, password) }

        override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> {
            lookups++
            val account = accounts[credentials.username]
            return if (account != null && account.password == credentials.password) {
                Result.Success(account.principal)
            } else {
                Result.Failure(AppError.Unauthorized())
            }
        }

        override suspend fun deleteUser(id: Id): Result<Unit> {
            if (failNextMutation) return Result.Failure(AppError.Conflict("no"))
            accounts.values.removeAll { it.principal.id == id }
            return Result.Success(Unit)
        }

        override suspend fun updateUserRole(
            id: Id,
            role: Role,
        ): Result<UserPrincipal> {
            if (failNextMutation) return Result.Failure(AppError.Conflict("no"))
            val account = accounts.values.first { it.principal.id == id }
            account.principal = account.principal.copy(roles = setOf(role))
            return Result.Success(account.principal)
        }

        override suspend fun changePassword(
            userId: Id,
            currentPassword: String,
            newPassword: String,
        ): Result<Unit> {
            if (failNextMutation) return Result.Failure(AppError.Conflict("no"))
            accounts.values.first { it.principal.id == userId }.password = newPassword
            return Result.Success(Unit)
        }

        override suspend fun register(
            credentials: Credentials,
            role: Role,
        ): Result<UserPrincipal> = error("unused")

        override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> = error("unused")

        override suspend fun validateSession(token: String): Result<UserPrincipal> = error("unused")

        override suspend fun invalidateSession(token: String): Result<Unit> = error("unused")

        override suspend fun listUsers(): Result<List<UserPrincipal>> = error("unused")

        override suspend fun cleanupExpiredSessions(): Result<Unit> = error("unused")
    }

    private var now = 0L
    private val delegate = FakeAuth()
    private val auth = CachingAuthApi(delegate, ttlMs = 60_000L, clock = { now })

    private fun creds(
        name: String,
        password: String,
    ) = Credentials(name, password)

    @Test
    fun `a validated credential is served from the cache`() =
        runBlocking<Unit> {
            delegate.add("alice", "secret-pw")

            assertIs<Result.Success<*>>(auth.validateCredentials(creds("alice", "secret-pw")))
            assertIs<Result.Success<*>>(auth.validateCredentials(creds("alice", "secret-pw")))

            assertEquals(1, delegate.lookups)
        }

    @Test
    fun `failures are never cached`() =
        runBlocking<Unit> {
            delegate.add("alice", "secret-pw")

            repeat(3) { assertIs<Result.Failure>(auth.validateCredentials(creds("alice", "wrong"))) }

            assertEquals(3, delegate.lookups)
        }

    @Test
    fun `an entry expires after the ttl`() =
        runBlocking<Unit> {
            delegate.add("alice", "secret-pw")
            auth.validateCredentials(creds("alice", "secret-pw"))

            now = 60_001L
            auth.validateCredentials(creds("alice", "secret-pw"))

            assertEquals(2, delegate.lookups)
        }

    @Test
    fun `deleting a user ends their cached access immediately`() =
        runBlocking<Unit> {
            val alice = delegate.add("alice", "secret-pw")
            auth.validateCredentials(creds("alice", "secret-pw"))

            auth.deleteUser(alice.id)

            assertIs<Result.Failure>(auth.validateCredentials(creds("alice", "secret-pw")))
        }

    @Test
    fun `demoting a user is visible to the next request`() =
        runBlocking<Unit> {
            val admin = delegate.add("root", "secret-pw", Role.ADMIN)
            auth.validateCredentials(creds("root", "secret-pw"))

            auth.updateUserRole(admin.id, Role.USER)

            val principal = (auth.validateCredentials(creds("root", "secret-pw")) as Result.Success).value
            assertEquals(setOf(Role.USER), principal.roles)
        }

    @Test
    fun `changing the password ends access with the old one`() =
        runBlocking<Unit> {
            val alice = delegate.add("alice", "secret-pw")
            auth.validateCredentials(creds("alice", "secret-pw"))

            auth.changePassword(alice.id, "secret-pw", "another-pw")

            assertIs<Result.Failure>(auth.validateCredentials(creds("alice", "secret-pw")))
            assertIs<Result.Success<*>>(auth.validateCredentials(creds("alice", "another-pw")))
        }

    @Test
    fun `a failed mutation keeps the cache`() =
        runBlocking<Unit> {
            val alice = delegate.add("alice", "secret-pw")
            auth.validateCredentials(creds("alice", "secret-pw"))
            delegate.failNextMutation = true

            auth.deleteUser(alice.id)
            auth.validateCredentials(creds("alice", "secret-pw"))

            assertEquals(1, delegate.lookups)
        }

    @Test
    fun `invalidating one user keeps the others cached`() =
        runBlocking<Unit> {
            val alice = delegate.add("alice", "secret-pw")
            delegate.add("bob", "other-pw")
            auth.validateCredentials(creds("alice", "secret-pw"))
            auth.validateCredentials(creds("bob", "other-pw"))

            auth.deleteUser(alice.id)
            auth.validateCredentials(creds("bob", "other-pw"))

            assertEquals(2, delegate.lookups)
        }
}
