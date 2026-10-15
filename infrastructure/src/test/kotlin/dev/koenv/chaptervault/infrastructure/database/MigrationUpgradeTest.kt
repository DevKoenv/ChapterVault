package dev.koenv.chaptervault.infrastructure.database

import org.flywaydb.core.api.FlywayException
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
    private val v1 = "V1__first.sql" to "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);"
    private val v2 =
        "V2__add_tags.sql" to
            "CREATE TABLE tags (id INTEGER PRIMARY KEY);\nALTER TABLE notes ADD COLUMN tag TEXT;"
    private val brokenV2 =
        "V2__broken.sql" to
            "CREATE TABLE tags (id INTEGER PRIMARY KEY);\nALTER TABLE does_not_exist ADD COLUMN x TEXT;"

    /** A folder holding exactly the given migration files, in the form Flyway takes as a location. */
    private fun migrations(
        dir: Path,
        vararg files: Pair<String, String>,
    ): String {
        val folder = Files.createDirectories(dir.resolve("migrations"))
        folder.listDirectoryEntries().forEach { Files.delete(it) }
        files.forEach { (name, sql) -> Files.writeString(folder.resolve(name), sql) }
        return "filesystem:$folder"
    }

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
        DatabaseMigrations.migrate(open(dir), migrations(dir, v1))

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `upgrading copies the database first and keeps the data`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db, migrations(dir, v1))
        transaction(db) { exec("INSERT INTO notes (body) VALUES ('keep me')") }

        DatabaseMigrations.migrate(db, migrations(dir, v1, v2))

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
        DatabaseMigrations.migrate(db, migrations(dir, v1))

        DatabaseMigrations.migrate(db, migrations(dir, v1))

        assertEquals(emptyList(), backups(dir))
    }

    @Test
    fun `an interrupted migration leaves the database as it was and keeps the backup`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        DatabaseMigrations.migrate(db, migrations(dir, v1))
        transaction(db) { exec("INSERT INTO notes (body) VALUES ('keep me')") }

        assertFailsWith<FlywayException> { DatabaseMigrations.migrate(db, migrations(dir, v1, brokenV2)) }

        assertFalse("tags" in tables(db), "the first statement of the failed migration must be rolled back")
        assertEquals(
            1,
            transaction(db) {
                exec("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL") {
                    it.next()
                    it.getInt(1)
                }
            },
        )
        assertTrue(dir.resolve("chaptervault.db.pre-V2").exists())

        // fixing the migration and starting again works
        DatabaseMigrations.migrate(db, migrations(dir, v1, v2))
        assertTrue("tags" in tables(db))
    }

    @Test
    fun `an in-memory database is migrated without trying to copy it`(
        @TempDir dir: Path,
    ) {
        val url = "jdbc:sqlite:file:mem-upgrade?mode=memory&cache=shared"
        // a shared in-memory database only exists while some connection to it is open
        DriverManager.getConnection(url).use {
            val db = Database.connect(url, "org.sqlite.JDBC")
            DatabaseMigrations.migrate(db, migrations(dir, v1))

            DatabaseMigrations.migrate(db, migrations(dir, v1, v2))

            assertTrue("tags" in tables(db))
        }
    }

    @Test
    fun `a trigger and a semicolon inside a string are plain SQL`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        val sql =
            "CREATE TABLE a (id INTEGER PRIMARY KEY, note TEXT);\n" +
                "CREATE TABLE log (msg TEXT);\n" +
                "CREATE TRIGGER a_ins AFTER INSERT ON a BEGIN INSERT INTO log VALUES ('added; ok'); END;"
        DatabaseMigrations.migrate(db, migrations(dir, "V1__trigger.sql" to sql))

        transaction(db) { exec("INSERT INTO a (note) VALUES ('x')") }

        assertEquals(
            "added; ok",
            transaction(db) {
                exec("SELECT msg FROM log") {
                    it.next()
                    it.getString(1)
                }
            },
        )
    }

    @Test
    fun `a database from a pre-release build is adopted as version 1 and upgraded with a backup`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        // what the old migrator left behind: the V1 tables and its own bookkeeping table, no Flyway history
        transaction(db) {
            exec("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            exec("INSERT INTO notes (body) VALUES ('old data')")
            exec("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL, checksum TEXT)")
            exec("INSERT INTO schema_version VALUES (1, 'first', 'then', 'abc')")
        }

        DatabaseMigrations.migrate(db, migrations(dir, v1, v2))

        assertTrue("tags" in tables(db))
        assertEquals(listOf("chaptervault.db.pre-V2"), backups(dir))
        assertEquals(
            "old data",
            transaction(db) {
                exec("SELECT body FROM notes") {
                    it.next()
                    it.getString(1)
                }
            },
        )
    }

    @Test
    fun `a pre-release database that is not at the squashed V1 is refused with a pointer to the upgrade notes`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        transaction(db) {
            exec("CREATE TABLE notes (id INTEGER PRIMARY KEY)")
            exec("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL, checksum TEXT)")
            exec("INSERT INTO schema_version VALUES (1, 'old_first', 'then', 'abc')")
            exec("INSERT INTO schema_version VALUES (2, 'old_second', 'then', 'def')")
        }

        val error = assertFailsWith<IllegalStateException> { DatabaseMigrations.migrate(db, migrations(dir, v1, v2)) }

        assertTrue("Upgrading" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a non-empty database without Flyway history or the old bookkeeping table is refused`(
        @TempDir dir: Path,
    ) {
        val db = open(dir)
        transaction(db) { exec("CREATE TABLE something_else (id INTEGER)") }

        assertFailsWith<FlywayException> { DatabaseMigrations.migrate(db, migrations(dir, v1)) }
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
}
