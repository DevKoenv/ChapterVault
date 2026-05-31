package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.DatabaseMigrations
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationRepositoryTest {
    private lateinit var repo: NotificationRepository

    @BeforeEach
    fun setUp(
        @TempDir tempDir: Path,
    ) {
        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)
        repo = NotificationRepository()
    }

    @Test
    fun `createTarget stores target and returns it`() =
        runBlocking {
            val input =
                NotificationTargetInput(
                    name = "My NTFY",
                    type = "NTFY",
                    url = "https://ntfy.sh/mytopic",
                    token = "secret",
                    enabled = true,
                )
            val result = repo.createTarget(input)
            assertIs<Result.Success<*>>(result)
            val target = (result as Result.Success).value
            assertEquals("My NTFY", target.name)
            assertEquals("NTFY", target.type)
            assertEquals("https://ntfy.sh/mytopic", target.url)
            assertEquals("secret", target.token)
            assertTrue(target.enabled)
        }

    @Test
    fun `listTargets returns all targets`() =
        runBlocking {
            repo.createTarget(NotificationTargetInput("A", "DISCORD", "https://discord.com/api/webhooks/1"))
            repo.createTarget(NotificationTargetInput("B", "WEBHOOK", "https://example.com"))
            val targets = repo.listTargets()
            assertEquals(2, targets.size)
        }

    @Test
    fun `findTarget returns target by id`() =
        runBlocking {
            val created =
                (
                    repo.createTarget(
                        NotificationTargetInput("Gotify", "GOTIFY", "https://gotify.example.com"),
                    ) as Result.Success
                ).value

            val found = repo.findTarget(created.id)
            assertIs<Result.Success<*>>(found)
            assertEquals(created.id, (found as Result.Success).value.id)
        }

    @Test
    fun `findTarget returns NotFound for unknown id`() =
        runBlocking {
            val result = repo.findTarget(Id.generate())
            assertIs<Result.Failure>(result)
        }

    @Test
    fun `updateTarget applies patch fields`() =
        runBlocking {
            val created =
                (
                    repo.createTarget(
                        NotificationTargetInput("Old Name", "NTFY", "https://ntfy.sh/old", enabled = true),
                    ) as Result.Success
                ).value

            val result = repo.updateTarget(created.id, NotificationTargetPatch(name = "New Name", enabled = false))
            assertIs<Result.Success<*>>(result)
            val updated = (result as Result.Success).value
            assertEquals("New Name", updated.name)
            assertEquals(false, updated.enabled)
            assertEquals("https://ntfy.sh/old", updated.url)
        }

    @Test
    fun `deleteTarget removes the target`() =
        runBlocking {
            val created =
                (
                    repo.createTarget(
                        NotificationTargetInput("Del", "WEBHOOK", "https://example.com/hook"),
                    ) as Result.Success
                ).value

            assertIs<Result.Success<*>>(repo.deleteTarget(created.id))
            assertEquals(0, repo.listTargets().size)
        }

    @Test
    fun `deleteTarget returns NotFound for unknown id`() =
        runBlocking {
            assertIs<Result.Failure>(repo.deleteTarget(Id.generate()))
        }
}
