package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRepositoryTest {
    private lateinit var repo: UserRepository

    @BeforeAll
    fun setup() {
        val dbFile = Files.createTempFile("userrepo-test", ".sqlite").toFile()
        dbFile.deleteOnExit()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                dev.koenv.chaptervault.infrastructure.database.entities.UserTable,
                dev.koenv.chaptervault.infrastructure.database.entities.SessionTable,
            )
        }
        repo = UserRepository()
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(
                dev.koenv.chaptervault.infrastructure.database.entities.SessionTable,
                dev.koenv.chaptervault.infrastructure.database.entities.UserTable,
            )
            SchemaUtils.create(
                dev.koenv.chaptervault.infrastructure.database.entities.UserTable,
                dev.koenv.chaptervault.infrastructure.database.entities.SessionTable,
            )
        }
    }

    @Test
    fun `register creates a new user`() {
        runBlocking {
            val result = repo.register(Credentials("alice", "password123"))
            assertIs<Result.Success<*>>(result)
            val principal = (result as Result.Success).value
            assertEquals("alice", principal.username)
            assertEquals(setOf(Role.USER), principal.roles)
        }
    }

    @Test
    fun `register with ADMIN role creates admin user`() {
        runBlocking {
            val result = repo.register(Credentials("admin", "secret123"), Role.ADMIN)
            assertIs<Result.Success<*>>(result)
            val principal = (result as Result.Success).value
            assertEquals(setOf(Role.ADMIN), principal.roles)
        }
    }

    @Test
    fun `register returns Conflict when username taken`() {
        runBlocking {
            repo.register(Credentials("bob", "password1"))
            val result = repo.register(Credentials("bob", "other-pass"))
            assertIs<Result.Failure>(result)
            assertIs<AppError.Conflict>((result as Result.Failure).error)
        }
    }

    @Test
    fun `authenticate succeeds with correct password`() {
        runBlocking {
            repo.register(Credentials("charlie", "mypassword"))
            val result = repo.authenticate(Credentials("charlie", "mypassword"))
            assertIs<Result.Success<*>>(result)
            val (principal, session) = (result as Result.Success).value
            assertEquals("charlie", principal.username)
            assertEquals(principal.id, session.userId)
        }
    }

    @Test
    fun `authenticate returns Unauthorized for wrong password`() {
        runBlocking {
            repo.register(Credentials("dave", "correct-pw"))
            val result = repo.authenticate(Credentials("dave", "wrong"))
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }

    @Test
    fun `authenticate returns Unauthorized for unknown user`() {
        runBlocking {
            val result = repo.authenticate(Credentials("nobody", "password1"))
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }

    @Test
    fun `validateSession returns principal for valid token`() {
        runBlocking {
            repo.register(Credentials("eve", "password1"))
            val authResult = repo.authenticate(Credentials("eve", "password1"))
            val token = ((authResult as Result.Success).value).second.token
            val result = repo.validateSession(token)
            assertIs<Result.Success<*>>(result)
            assertEquals("eve", (result as Result.Success).value.username)
        }
    }

    @Test
    fun `validateSession returns Unauthorized for unknown token`() {
        runBlocking {
            val result = repo.validateSession("no-such-token")
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }

    @Test
    fun `invalidateSession removes the session`() {
        runBlocking {
            repo.register(Credentials("frank", "password1"))
            val token = ((repo.authenticate(Credentials("frank", "password1")) as Result.Success).value).second.token
            repo.invalidateSession(token)
            val result = repo.validateSession(token)
            assertIs<Result.Failure>(result)
        }
    }

    @Test
    fun `validateSession returns Unauthorized for expired token`() {
        runBlocking {
            repo.register(Credentials("grace", "password1"))
            val userId =
                transaction {
                    dev.koenv.chaptervault.infrastructure.database.entities.UserTable
                        .selectAll()
                        .where { dev.koenv.chaptervault.infrastructure.database.entities.UserTable.username eq "grace" }
                        .single()[dev.koenv.chaptervault.infrastructure.database.entities.UserTable.id]
                }
            val pastExpiry =
                java.time.Instant
                    .now()
                    .minusSeconds(3600)
                    .toKotlinInstant()
            transaction {
                dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.insert {
                    it[dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.id] =
                        dev.koenv.chaptervault.shared.utils.Id
                            .generate()
                            .toString()
                    it[dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.userId] = userId
                    it[dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.tokenHash] =
                        sessionTokenHash("expired-token-xyz")
                    it[dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.expiresAt] = pastExpiry
                    it[dev.koenv.chaptervault.infrastructure.database.entities.SessionTable.createdAt] = pastExpiry
                }
            }
            val result = repo.validateSession("expired-token-xyz")
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }
}
