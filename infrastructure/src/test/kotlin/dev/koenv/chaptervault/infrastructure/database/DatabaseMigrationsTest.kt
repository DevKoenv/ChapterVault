package dev.koenv.chaptervault.infrastructure.database

import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseMigrationsTest {
    private val shipped =
        Path
            .of(javaClass.classLoader.getResource("db/migration/sqlite")!!.toURI())
            .let { folder -> Files.list(folder).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList() } }

    private fun open(dir: Path) = Database.connect("jdbc:sqlite:${dir.resolve("test.db")}", "org.sqlite.JDBC")

    private fun history(db: Database): List<Pair<String, Int?>> =
        transaction(db) {
            exec("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank") { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("version") to rs.getObject("checksum") as Int?)
                }
            }!!
        }

    @Test
    fun `migrate applies every shipped migration and stores checksums`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db)

        val rows = history(db)
        assertEquals(shipped.size, rows.size)
        rows.forEach { (_, checksum) -> assertNotNull(checksum) }
    }

    @Test
    fun `migrate is idempotent, running twice does not duplicate rows`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db)
        DatabaseMigrations.migrate(db)

        assertEquals(shipped.size, history(db).size)
    }

    @Test
    fun `migrate aborts when a stored checksum does not match`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db)
        transaction(db) { exec("UPDATE flyway_schema_history SET checksum = 12345 WHERE version = '1'") }

        assertFailsWith<FlywayException> { DatabaseMigrations.migrate(db) }
    }

    @Test
    fun `the shipped schema has the columns and tables later fixes rely on`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db)
        transaction(db) {
            exec("SELECT author, artist, year, upstream_status FROM series LIMIT 0") { }
            exec("SELECT series_id, genre FROM series_genres LIMIT 0") { }
            val dropped =
                exec("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('extension_configs', 'extension_registries')") { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }!!
            assertTrue(dropped.isEmpty(), "unexpected tables: $dropped")
        }
    }

    @Test
    fun `migration versions count up from 1 without gaps`() {
        val versions = shipped.map { Regex("V(\\d+)__").find(it)!!.groupValues[1].toInt() }.sorted()
        assertEquals((1..shipped.size).toList(), versions)
    }
}
