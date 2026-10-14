package dev.koenv.chaptervault.infrastructure.database

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationUpgradeTest {
    private val v1 = DatabaseMigrations.Migration(1, "first", listOf("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)"))
    private val v2 =
        DatabaseMigrations.Migration(
            2,
            "add_tags",
            listOf("CREATE TABLE tags (id INTEGER PRIMARY KEY)", "ALTER TABLE notes ADD COLUMN tag TEXT"),
        )
    private val brokenV2 =
        DatabaseMigrations.Migration(
            2,
            "broken",
            listOf("CREATE TABLE tags (id INTEGER PRIMARY KEY)", "ALTER TABLE does_not_exist ADD COLUMN x TEXT"),
        )

    private fun open(dir: Path): Database = Database.connect("jdbc:sqlite:${dir.resolve("chaptervault.db")}", "org.sqlite.JDBC")

    private fun tables(db: Database): Set<String> =
        transaction(db) {
            exec("SELECT name FROM sqlite_master WHERE type='table'") { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }!!
        }

    private fun backups(dir: Path) = dir.listDirectoryEntries().map { it.name }.filter { ".pre-V" in it }

    @Test
    fun `a fresh database is created without a backup`(
        @TempDir dir: Path,
    ) {
        DatabaseMigrations.migrate(open(dir), listOf(v1))

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `upgrading copies the database first and keeps the data`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db, listOf(v1))
        transaction(db) { exec("INSERT INTO notes (body) VALUES ('keep me')") }

        DatabaseMigrations.migrate(db, listOf(v1, v2))

        assertEquals(listOf("chaptervault.db.pre-V2"), backups(dir))
        assertTrue("tags" in tables(db))
        // the copy is the database as it was before V2: it has the data but not the new table
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("chaptervault.db.pre-V2")}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT body FROM notes").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("keep me", rs.getString(1))
                }
                st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'tags'").use { rs -> assertFalse(rs.next()) }
            }
        }
    }

    @Test
    fun `nothing pending means no backup`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db, listOf(v1))

        DatabaseMigrations.migrate(db, listOf(v1))

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `an interrupted migration leaves the database as it was and keeps the backup`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db, listOf(v1))
        transaction(db) { exec("INSERT INTO notes (body) VALUES ('keep me')") }

        assertFailsWith<ExposedSQLException> { DatabaseMigrations.migrate(db, listOf(v1, brokenV2)) }

        assertFalse("tags" in tables(db), "the first statement of the failed migration must be rolled back")
        assertEquals(
            1,
            transaction(db) {
                exec("SELECT COUNT(*) FROM schema_version") {
                    it.next()
                    it.getInt(1)
                }
            },
        )
        assertTrue(dir.resolve("chaptervault.db.pre-V2").exists())

        // fixing the migration and starting again works
        DatabaseMigrations.migrate(db, listOf(v1, v2))
        assertTrue("tags" in tables(db))
    }

    @Test
    fun `an in-memory database is migrated without trying to copy it`() {
        val url = "jdbc:sqlite:file:mem-upgrade?mode=memory&cache=shared"
        // a shared in-memory database only exists while some connection to it is open
        DriverManager.getConnection(url).use {
            val db = Database.connect(url, "org.sqlite.JDBC")
            DatabaseMigrations.migrate(db, listOf(v1))

            DatabaseMigrations.migrate(db, listOf(v1, v2))

            assertTrue("tags" in tables(db))
        }
    }

    @Test
    fun `the shipped schema keeps the invariants later fixes rely on`(
        @TempDir dir: Path,
    ) {
        val db =
            Database.connect(
                "jdbc:sqlite:${dir.resolve("chaptervault.db")}",
                "org.sqlite.JDBC",
                setupConnection = { conn -> conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") } },
            )
        DatabaseMigrations.migrate(db)

        transaction(db) {
            exec("INSERT INTO users (id, username, password_hash, roles, created_at) VALUES ('1', 'Admin', 'x', 'ADMIN', 'now')")
        }
        // usernames are unique ignoring case
        assertFailsWith<ExposedSQLException> {
            transaction(db) {
                exec("INSERT INTO users (id, username, password_hash, roles, created_at) VALUES ('2', 'admin', 'x', 'USER', 'now')")
            }
        }
        // deleting a user with sessions works with foreign keys on
        transaction(db) {
            exec("INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at) VALUES ('s', '1', 'h', 'later', 'now')")
            exec("DELETE FROM users WHERE id = '1'")
            assertEquals(
                0,
                exec("SELECT COUNT(*) FROM sessions") {
                    it.next()
                    it.getInt(1)
                },
            )
        }
        // the same source series cannot be added twice in one language
        transaction(db) {
            val insert =
                "INSERT INTO series (id, title, connector_id, external_id, status, language, added_at, updated_at) " +
                    "VALUES ('%s', 't', 'mangadex', 'e', 'IN_LIBRARY', '%s', 'now', 'now')"
            exec(insert.format("a", "en"))
            exec(insert.format("b", "nl"))
        }
        assertFailsWith<ExposedSQLException> {
            transaction(db) {
                exec(
                    "INSERT INTO series (id, title, connector_id, external_id, status, language, added_at, updated_at) " +
                        "VALUES ('c', 't', 'mangadex', 'e', 'IN_LIBRARY', 'en', 'now', 'now')",
                )
            }
        }
        transaction(db) { exec("SELECT run_after FROM tasks LIMIT 0") { } }
    }

    @Test
    fun `every migration file is listed in the index in order`() {
        val folder = Path.of(javaClass.classLoader.getResource("db/migration")!!.toURI())
        val files =
            Files.list(folder).use { s ->
                s.map { it.fileName.toString() }.filter { Regex("V\\d+__.+\\.sql").matches(it) }.toList()
            }
        val sorted = files.sortedBy { Regex("V(\\d+)__").find(it)!!.groupValues[1].toInt() }

        val listed =
            javaClass.classLoader
                .getResource("db/migration/index.txt")!!
                .readText()
                .lines()
                .filter { it.isNotBlank() }

        assertEquals(sorted, listed)
        assertEquals((1..listed.size).toList(), DatabaseMigrations.migrations.map { it.version })
    }
}
