package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.repositories.UserRepository
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminBootstrapTest {
    private lateinit var users: UserRepository
    private val announcements = mutableListOf<String>()

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("bootstrap.db")}"))
        users = UserRepository()
        announcements.clear()
    }

    @AfterEach
    fun tearDown() {
        DatabaseFactory.close()
    }

    private fun bootstrap(env: Map<String, String> = emptyMap()) =
        AdminBootstrap(users, env = { env[it] }, announce = { announcements += it })

    private fun usernames(): List<String> = runBlocking { (users.listUsers() as Result.Success).value.map { it.username } }

    @Test
    fun `an empty install gets an admin with a generated password that is announced once`() =
        runBlocking<Unit> {
            bootstrap().run()

            assertEquals(listOf("admin"), usernames())
            assertEquals(1, announcements.size)
            val password =
                announcements
                    .single()
                    .lines()
                    .first { "password" in it.lowercase() }
                    .substringAfterLast(' ')
            assertTrue(password.length >= 16, "generated password too short: $password")
            val login = users.authenticate(Credentials("admin", password))
            assertIs<Result.Success<*>>(login)
            assertEquals(setOf(Role.ADMIN), (login as Result.Success).value.first.roles)
        }

    @Test
    fun `generated passwords are long, unique and safe to type or paste`() {
        val passwords = List(20) { generateAdminPassword() }

        assertEquals(20, passwords.toSet().size)
        passwords.forEach { assertTrue(it.length >= 20 && it.all { c -> c.isLetterOrDigit() }, it) }
    }

    @Test
    fun `the password from the environment is used and never announced`() =
        runBlocking<Unit> {
            bootstrap(mapOf("CHAPTERVAULT_ADMIN_PASS" to "a-chosen-password")).run()

            assertIs<Result.Success<*>>(users.authenticate(Credentials("admin", "a-chosen-password")))
            assertTrue(announcements.none { "a-chosen-password" in it })
        }

    @Test
    fun `the username can come from the environment`() =
        runBlocking<Unit> {
            bootstrap(mapOf("CHAPTERVAULT_ADMIN_USER" to "koen", "CHAPTERVAULT_ADMIN_PASS" to "a-chosen-password")).run()

            assertEquals(listOf("koen"), usernames())
        }

    @Test
    fun `a password that breaks the policy is refused and nothing is created`() {
        assertFailsWith<IllegalStateException> {
            runBlocking { bootstrap(mapOf("CHAPTERVAULT_ADMIN_PASS" to "short")).run() }
        }
        assertEquals(emptyList(), usernames())
    }

    @Test
    fun `a deleted admin is not re-created while other users exist`() =
        runBlocking<Unit> {
            bootstrap(mapOf("CHAPTERVAULT_ADMIN_PASS" to "a-chosen-password")).run()
            val other = (users.register(Credentials("root2", "another-password"), Role.ADMIN) as Result.Success).value
            val admin = (users.listUsers() as Result.Success).value.first { it.username == "admin" }
            users.deleteUser(admin.id)
            announcements.clear()

            bootstrap(mapOf("CHAPTERVAULT_ADMIN_PASS" to "a-chosen-password")).run()

            assertEquals(listOf(other.username), usernames())
            assertTrue(announcements.isEmpty())
        }

    @Test
    fun `running twice creates one admin and announces once`() =
        runBlocking<Unit> {
            bootstrap().run()
            bootstrap().run()

            assertEquals(listOf("admin"), usernames())
            assertEquals(1, announcements.size)
        }
}
