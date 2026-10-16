package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Account lifecycle tests against the real migrated schema with `PRAGMA foreign_keys = ON`, exactly as the server
 * runs. [UserRepositoryTest] builds its tables with SchemaUtils and no foreign key pragma, so it cannot see
 * constraint failures.
 */
class UserLifecycleTest {
    private lateinit var repo: UserRepository

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("lifecycle.db")}"))
        repo = UserRepository()
    }

    @AfterEach
    fun tearDown() {
        DatabaseFactory.close()
    }

    private fun <T> Result<T>.value(): T = (this as Result.Success).value

    private suspend fun registerAndLogin(
        name: String,
        role: Role = Role.USER,
    ): Pair<dev.koenv.chaptervault.kernel.auth.UserPrincipal, String> {
        val principal = repo.register(Credentials(name, "password-$name"), role).value()
        val session = repo.authenticate(Credentials(name, "password-$name")).value().second
        return principal to session.token
    }

    @Test
    fun `deleteUser succeeds for a user who has a live session`() =
        runBlocking<Unit> {
            val (user, token) = registerAndLogin("alice")

            val result = repo.deleteUser(user.id)

            assertIs<Result.Success<*>>(result)
            assertIs<Result.Failure>(repo.validateSession(token))
        }

    @Test
    fun `setPassword replaces the password and signs the user out everywhere`() =
        runBlocking<Unit> {
            val (user, token) = registerAndLogin("alice")

            assertIs<Result.Success<Unit>>(repo.setPassword(user.id, "a-brand-new-password"))

            assertIs<Result.Failure>(repo.validateSession(token))
            assertIs<Result.Failure>(repo.validateCredentials(Credentials("alice", "password-alice")))
            assertIs<Result.Success<*>>(repo.validateCredentials(Credentials("alice", "a-brand-new-password")))
        }

    @Test
    fun `setPassword checks the password policy and that the user exists`() =
        runBlocking<Unit> {
            val (user, _) = registerAndLogin("alice")

            assertIs<AppError.ValidationError>((repo.setPassword(user.id, "short") as Result.Failure).error)
            assertIs<Result.Success<*>>(repo.validateCredentials(Credentials("alice", "password-alice")))
            assertIs<AppError.NotFound>((repo.setPassword(Id.generate(), "a-brand-new-password") as Result.Failure).error)
        }

    @Test
    fun `register rejects a password shorter than the minimum`() =
        runBlocking<Unit> {
            val result = repo.register(Credentials("shorty", "1234567"))

            assertIs<AppError.ValidationError>((result as Result.Failure).error)
            assertEquals(0, repo.listUsers().value().size)
        }

    @Test
    fun `register rejects a password BCrypt would truncate`() =
        runBlocking<Unit> {
            // 25 three-byte characters: well over 8 characters, but 75 bytes once encoded
            val result = repo.register(Credentials("longy", "€".repeat(25)))

            assertIs<AppError.ValidationError>((result as Result.Failure).error)
        }

    @Test
    fun `changePassword rejects a short new password and keeps the old one and the sessions`() =
        runBlocking<Unit> {
            val (user, token) = registerAndLogin("erin")

            val result = repo.changePassword(user.id, "password-erin", "short")

            assertIs<AppError.ValidationError>((result as Result.Failure).error)
            assertIs<Result.Success<*>>(repo.validateSession(token))
            assertIs<Result.Success<*>>(repo.authenticate(Credentials("erin", "password-erin")))
        }

    @Test
    fun `deleteUser returns NotFound for an unknown id`() =
        runBlocking<Unit> {
            val result =
                repo.deleteUser(
                    dev.koenv.chaptervault.shared.utils.Id
                        .generate(),
                )

            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }

    @Test
    fun `updateUserRole drops the user's sessions`() =
        runBlocking<Unit> {
            val (user, token) = registerAndLogin("bob")

            repo.updateUserRole(user.id, Role.GUEST).value()

            assertIs<Result.Failure>(repo.validateSession(token))
        }

    @Test
    fun `changePassword revokes every session of the user`() =
        runBlocking<Unit> {
            val (user, token) = registerAndLogin("carol")
            val second =
                repo
                    .authenticate(Credentials("carol", "password-carol"))
                    .value()
                    .second.token

            repo.changePassword(user.id, "password-carol", "a-new-password").value()

            assertIs<Result.Failure>(repo.validateSession(token))
            assertIs<Result.Failure>(repo.validateSession(second))
            assertIs<Result.Success<*>>(repo.authenticate(Credentials("carol", "a-new-password")))
        }

    @Test
    fun `changePassword leaves other users' sessions alone`() =
        runBlocking<Unit> {
            val (carol, _) = registerAndLogin("carol")
            val (_, daveToken) = registerAndLogin("dave")

            repo.changePassword(carol.id, "password-carol", "a-new-password").value()

            assertIs<Result.Success<*>>(repo.validateSession(daveToken))
        }

    @Test
    fun `the last admin cannot be deleted`() =
        runBlocking<Unit> {
            val (admin, _) = registerAndLogin("root", Role.ADMIN)

            val result = repo.deleteUser(admin.id)

            assertIs<AppError.Conflict>((result as Result.Failure).error)
            assertEquals(1, repo.listUsers().value().size)
        }

    @Test
    fun `the last admin cannot be demoted`() =
        runBlocking<Unit> {
            val (admin, token) = registerAndLogin("root", Role.ADMIN)

            val result = repo.updateUserRole(admin.id, Role.USER)

            assertIs<AppError.Conflict>((result as Result.Failure).error)
            assertEquals(setOf(Role.ADMIN), repo.validateSession(token).value().roles)
        }

    @Test
    fun `with two admins one can be demoted and the remaining one is then protected`() =
        runBlocking<Unit> {
            val (first, _) = registerAndLogin("root", Role.ADMIN)
            val (second, _) = registerAndLogin("root2", Role.ADMIN)

            assertIs<Result.Success<*>>(repo.updateUserRole(first.id, Role.USER))
            assertIs<Result.Failure>(repo.deleteUser(second.id))
        }

    // ---- H6: sessions ----

    private fun sessionRows(): List<Triple<String, String, String>> =
        transaction {
            SessionTable.selectAll().map {
                Triple(
                    it[SessionTable.userId],
                    it[SessionTable.tokenHash],
                    it[SessionTable.expiresAt].toString(),
                )
            }
        }

    @Test
    fun `the bearer token is never stored, only its hash`() =
        runBlocking<Unit> {
            val (_, token) = registerAndLogin("alice")

            val (_, stored, _) = sessionRows().single()

            assertNotEquals(token, stored)
            assertEquals(sessionTokenHash(token), stored)
            assertEquals(64, stored.length)
        }

    @Test
    fun `a session in use for more than a day gets its lifetime extended`() =
        runBlocking<Unit> {
            val (_, token) = registerAndLogin("alice")
            transaction {
                SessionTable.update {
                    it[expiresAt] =
                        java.time.Instant
                            .now()
                            .plusSeconds(10L * 24 * 3600)
                            .toKotlinInstant()
                }
            }

            assertIs<Result.Success<*>>(repo.validateSession(token))

            val expires = transaction { SessionTable.selectAll().single()[SessionTable.expiresAt].toJavaInstant() }
            assertTrue(
                expires.isAfter(
                    java.time.Instant
                        .now()
                        .plusSeconds(25L * 24 * 3600),
                ),
                "expires $expires",
            )
        }

    @Test
    fun `a user keeps at most 20 sessions and the oldest one is dropped`() =
        runBlocking<Unit> {
            repo.register(Credentials("alice", "password-alice")).value()
            val tokens =
                List(22) {
                    repo
                        .authenticate(Credentials("alice", "password-alice"))
                        .value()
                        .second.token
                }

            assertEquals(20, sessionRows().size)
            assertIs<Result.Failure>(repo.validateSession(tokens.first()))
            assertIs<Result.Success<*>>(repo.validateSession(tokens.last()))
        }

    // ---- H14: accounts ----

    @Test
    fun `usernames are unique regardless of case`() =
        runBlocking<Unit> {
            repo.register(Credentials("Alice", "password-alice")).value()

            val result = repo.register(Credentials("alice", "password-other"))

            assertIs<AppError.Conflict>((result as Result.Failure).error)
        }

    @Test
    fun `login ignores the case of the username and keeps the spelling it was registered with`() =
        runBlocking<Unit> {
            repo.register(Credentials("Alice", "password-alice")).value()

            val principal = repo.authenticate(Credentials("ALICE", "password-alice")).value().first
            val basic = repo.validateCredentials(Credentials("alice", "password-alice")).value()

            assertEquals("Alice", principal.username)
            assertEquals(principal.id, basic.id)
        }

    @Test
    fun `a username is trimmed`() =
        runBlocking<Unit> {
            val principal = repo.register(Credentials("  bob  ", "password-bob")).value()

            assertEquals("bob", principal.username)
            assertIs<Result.Success<*>>(repo.authenticate(Credentials("bob", "password-bob")))
        }

    @Test
    fun `usernames that are blank or contain control characters are refused`() =
        runBlocking<Unit> {
            listOf("   ", "bob\u0000", "bo\nb", "bob\u202e").forEach { name ->
                val result = repo.register(Credentials(name, "password-bob"))
                assertIs<AppError.ValidationError>((result as Result.Failure).error, "'$name'")
            }
        }

    @Test
    fun `logging in as an unknown user takes about as long as a wrong password`() =
        runBlocking<Unit> {
            repo.register(Credentials("alice", "password-alice")).value()

            fun median(block: () -> Unit): Long =
                List(5) {
                    val t = System.nanoTime()
                    block()
                    System.nanoTime() - t
                }.sorted()[2]

            val wrongPassword = median { runBlocking { repo.authenticate(Credentials("alice", "wrong-password")) } }
            val unknownUser = median { runBlocking { repo.authenticate(Credentials("nobody", "wrong-password")) } }

            assertTrue(
                unknownUser > wrongPassword / 3,
                "unknown user answered in ${unknownUser / 1_000_000} ms, wrong password in ${wrongPassword / 1_000_000} ms",
            )
        }
}
