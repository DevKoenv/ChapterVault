package dev.koenv.chaptervault.infrastructure.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseMigrationsTest {

    @Test
    fun `migrate applies all migrations and stores checksums`(@TempDir dir: Path) {
        val db = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)

        transaction(db) {
            val rows = exec("SELECT version, checksum FROM schema_version ORDER BY version") { rs ->
                buildList {
                    while (rs.next()) add(rs.getInt("version") to rs.getString("checksum"))
                }
            }!!
            assertTrue(rows.isNotEmpty())
            rows.forEach { (_, checksum) -> assertNotNull(checksum) }
            assertEquals(DatabaseMigrations.migrations.size, rows.size)
        }
    }

    @Test
    fun `migrate is idempotent — running twice does not duplicate rows`(@TempDir dir: Path) {
        val db = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)
        DatabaseMigrations.migrate(db)

        transaction(db) {
            val count = exec("SELECT COUNT(*) AS cnt FROM schema_version") { rs ->
                rs.next(); rs.getInt("cnt")
            }!!
            assertEquals(DatabaseMigrations.migrations.size, count)
        }
    }

    @Test
    fun `migrate aborts when stored checksum does not match`(@TempDir dir: Path) {
        val db = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)

        transaction(db) {
            exec("UPDATE schema_version SET checksum = 'deadbeef' WHERE version = 1")
        }

        assertFailsWith<IllegalStateException> {
            DatabaseMigrations.migrate(db)
        }
    }

    @Test
    fun `checksum is deterministic for the same statements`() {
        val m1 = DatabaseMigrations.Migration(1, "test", listOf("SELECT 1", "SELECT 2"))
        val m2 = DatabaseMigrations.Migration(1, "test", listOf("SELECT 1", "SELECT 2"))
        assertEquals(m1.checksum, m2.checksum)
    }

    @Test
    fun `checksum differs when statements differ`() {
        val m1 = DatabaseMigrations.Migration(1, "test", listOf("SELECT 1"))
        val m2 = DatabaseMigrations.Migration(1, "test", listOf("SELECT 2"))
        assertTrue(m1.checksum != m2.checksum)
    }
}
