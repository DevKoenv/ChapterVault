package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserRepository
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the PostgreSQL path end to end: migration, and round-tripping the columns whose type differs
 * from the SQLite schema (bool(), timestamp()) where a mismatch would surface as a real JDBC error.
 *
 * Needs a real PostgreSQL reachable at CHAPTERVAULT_TEST_POSTGRES_URL (default: a local instance on 15432,
 * matching `docker run -e POSTGRES_PASSWORD=testpass -e POSTGRES_DB=chaptervault -p 15432:5432 postgres:16-alpine`).
 * Skips cleanly, rather than failing, when that database cannot be reached.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDatabaseTest {
    private val url = System.getenv("CHAPTERVAULT_TEST_POSTGRES_URL") ?: "jdbc:postgresql://localhost:15432/chaptervault"
    private val username = System.getenv("CHAPTERVAULT_TEST_POSTGRES_USER") ?: "postgres"
    private val password = System.getenv("CHAPTERVAULT_TEST_POSTGRES_PASSWORD") ?: "testpass"
    private lateinit var fileStorage: FileStorage

    @BeforeAll
    fun setup(
        @TempDir dir: Path,
    ) {
        val reachable =
            runCatching {
                DriverManager.getConnection(url, username, password).use { conn ->
                    // start every run from a clean schema so Flyway applies from scratch
                    conn.createStatement().use { it.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
                }
            }.isSuccess
        assumeTrue(reachable, "No PostgreSQL reachable at $url; skipping")

        DatabaseFactory.init(DatabaseConfig(driver = "org.postgresql.Driver", url = url, username = username, password = password))
        fileStorage =
            FileStorage(
                Files.createDirectories(dir.resolve("lib")),
                Files.createDirectories(dir.resolve("thumbs")),
                ArchiveWriterSelector(emptyList()),
            )
    }

    @AfterAll
    fun tearDown() {
        DatabaseFactory.close()
    }

    @Test
    fun `migrate applies the postgres schema and ping succeeds`() {
        assertTrue(runBlocking { DatabaseFactory.ping() })
    }

    @Test
    fun `a user round-trips through the native TIMESTAMP columns`() =
        runBlocking<Unit> {
            val users = UserRepository()
            val principal = (users.register(Credentials("pg-user", "password-pg-user")) as Result.Success).value
            val (loggedIn, session) = (users.authenticate(Credentials("pg-user", "password-pg-user")) as Result.Success).value

            assertEquals(principal.id, loggedIn.id)
            assertNotNull(session.expiresAt)
        }

    @Test
    fun `a series round-trips through the native BOOLEAN column`() =
        runBlocking<Unit> {
            val series = SeriesRepository(fileStorage)
            val added = (series.addToLibrary("mangadex", "pg-ext-1", "en", autoDownload = true) as Result.Success).value
            assertEquals(true, added.autoDownload)

            val updated = (series.updateSeries(added.id, autoDownload = false) as Result.Success).value
            assertEquals(false, updated.autoDownload)
        }
}
