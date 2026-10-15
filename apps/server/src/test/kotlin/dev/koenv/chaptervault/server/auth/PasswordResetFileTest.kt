package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.Session
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordResetFileTest {
    private val admin = UserPrincipal(Id.generate(), "admin", setOf(Role.ADMIN))
    private val alice = UserPrincipal(Id.generate(), "alice", setOf(Role.USER))

    private class FakeAuth(
        private val users: List<UserPrincipal>,
    ) : AuthApi {
        val resets = mutableListOf<Pair<Id, String>>()

        override suspend fun listUsers(): Result<List<UserPrincipal>> = Result.Success(users)

        override suspend fun setPassword(
            id: Id,
            newPassword: String,
        ): Result<Unit> {
            resets += id to newPassword
            return Result.Success(Unit)
        }

        override suspend fun register(
            credentials: Credentials,
            role: Role,
        ): Result<UserPrincipal> = error("unused")

        override suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>> = error("unused")

        override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> = error("unused")

        override suspend fun validateSession(token: String): Result<UserPrincipal> = error("unused")

        override suspend fun invalidateSession(token: String): Result<Unit> = error("unused")

        override suspend fun deleteUser(id: Id): Result<Unit> = error("unused")

        override suspend fun updateUserRole(
            id: Id,
            role: Role,
        ): Result<UserPrincipal> = error("unused")

        override suspend fun changePassword(
            userId: Id,
            currentPassword: String,
            newPassword: String,
        ): Result<Unit> = error("unused")

        override suspend fun cleanupExpiredSessions(): Result<Unit> = error("unused")
    }

    private val announced = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    private fun reset(
        auth: AuthApi,
        file: Path,
        remove: (Path) -> Unit = { Files.delete(it) },
    ) = runBlocking { PasswordResetFile(auth, file, { announced += it }, { warnings += it }, remove).run() }

    @Test
    fun `the user named in the file gets a new password, announced once, and the file is removed`(
        @TempDir dir: Path,
    ) {
        val auth = FakeAuth(listOf(admin, alice))
        val file = Files.writeString(dir.resolve("reset-password"), "  ADMIN\n")

        reset(auth, file)

        val (id, password) = auth.resets.single()
        assertEquals(admin.id, id)
        assertEquals(24, password.length)
        assertContains(announced.single(), "admin")
        assertContains(announced.single(), password)
        assertFalse(Files.exists(file))
    }

    @Test
    fun `an unknown username resets nothing and the file is still removed`(
        @TempDir dir: Path,
    ) {
        val auth = FakeAuth(listOf(admin))
        val file = Files.writeString(dir.resolve("reset-password"), "nobody")

        reset(auth, file)

        assertTrue(auth.resets.isEmpty())
        assertTrue(announced.isEmpty())
        assertContains(warnings.single(), "nobody")
        assertFalse(Files.exists(file))
    }

    @Test
    fun `without the file nothing happens`(
        @TempDir dir: Path,
    ) {
        val auth = FakeAuth(listOf(admin))

        reset(auth, dir.resolve("reset-password"))

        assertTrue(auth.resets.isEmpty())
        assertTrue(announced.isEmpty() && warnings.isEmpty())
    }

    @Test
    fun `a file that cannot be removed resets nothing, so the reset can never repeat at every start`(
        @TempDir dir: Path,
    ) {
        val auth = FakeAuth(listOf(admin))
        val file = Files.writeString(dir.resolve("reset-password"), "admin")

        reset(auth, file, remove = { throw IOException("read-only") })

        assertTrue(auth.resets.isEmpty())
        assertContains(warnings.single(), "reset-password")
    }
}
