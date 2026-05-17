package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
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
            val result = repo.register(Credentials("admin", "secret"), Role.ADMIN)
            assertIs<Result.Success<*>>(result)
            val principal = (result as Result.Success).value
            assertEquals(setOf(Role.ADMIN), principal.roles)
        }
    }

    @Test
    fun `register returns Conflict when username taken`() {
        runBlocking {
            repo.register(Credentials("bob", "pass"))
            val result = repo.register(Credentials("bob", "other"))
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
            repo.register(Credentials("dave", "correct"))
            val result = repo.authenticate(Credentials("dave", "wrong"))
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }

    @Test
    fun `authenticate returns Unauthorized for unknown user`() {
        runBlocking {
            val result = repo.authenticate(Credentials("nobody", "pass"))
            assertIs<Result.Failure>(result)
            assertIs<AppError.Unauthorized>((result as Result.Failure).error)
        }
    }

    @Test
    fun `validateSession returns principal for valid token`() {
        runBlocking {
            repo.register(Credentials("eve", "pass"))
            val authResult = repo.authenticate(Credentials("eve", "pass"))
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
            repo.register(Credentials("frank", "pass"))
            val token = ((repo.authenticate(Credentials("frank", "pass")) as Result.Success).value).second.token
            repo.invalidateSession(token)
            val result = repo.validateSession(token)
            assertIs<Result.Failure>(result)
        }
    }
}
