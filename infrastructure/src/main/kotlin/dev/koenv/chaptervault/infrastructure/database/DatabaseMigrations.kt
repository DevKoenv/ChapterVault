package dev.koenv.chaptervault.infrastructure.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

private const val SQLITE_URL_PREFIX = "jdbc:sqlite:"

/**
 * Applies the numbered `.sql` files in `resources/db/migration` that the database has not seen, with Flyway.
 *
 * Flyway verifies the checksum of every applied migration on startup and refuses to run if one was edited. All pending
 * migrations run in one transaction, so a failure leaves the schema as it was. Before changing an existing database it is
 * copied to `<file>.pre-V<N>` with `VACUUM INTO`, which also gives a consistent copy of a database in WAL mode.
 *
 * A database made by a pre-release build (it has a `schema_version` table but no Flyway history) is adopted as version 1.
 */
object DatabaseMigrations {
    private const val CLASSPATH_LOCATION = "classpath:db/migration"
    private const val LEGACY_TABLE = "schema_version"

    fun migrate(db: Database) = migrate(db, CLASSPATH_LOCATION)

    internal fun migrate(
        db: Database,
        location: String,
    ) {
        val adoptLegacy = hasTable(db.url, LEGACY_TABLE) && !hasTable(db.url, "flyway_schema_history")
        check(!adoptLegacy || legacyVersions(db.url) == listOf(1)) {
            "This database was made by a pre-release build and cannot be upgraded in place. " +
                "Export your reading progress and start with an empty data directory, see \"Upgrading\" in docs/operations.md."
        }
        val flyway =
            Flyway
                .configure()
                .dataSource(db.url, null, null)
                .locations(location)
                .group(true)
                .baselineOnMigrate(adoptLegacy)
                .baselineVersion("1")
                .initSql("PRAGMA foreign_keys = ON")
                .load()

        val info = flyway.info()
        val pending = info.pending().toList()
        // an adopted pre-release database already has V1; only what comes after it changes the data
        val changing = if (adoptLegacy) pending.filter { it.version.version != "1" } else pending
        val hasData = info.applied().isNotEmpty() || adoptLegacy
        if (changing.isNotEmpty() && hasData) {
            backupSource(db.url)?.let { backUp(it, changing.first().version.version) }
        }
        flyway.migrate()
        // the database that was just migrated is the one this thread works with next (what transaction(db) used to do here)
        TransactionManager.resetCurrent(TransactionManager.managerFor(db))
    }

    private class BackupSource(
        val url: String,
        val file: Path,
    )

    /** The database file a copy can be taken of, or null for in-memory databases. */
    private fun backupSource(url: String): BackupSource? {
        if (!url.startsWith(SQLITE_URL_PREFIX)) return null
        val location = url.removePrefix(SQLITE_URL_PREFIX).substringBefore('?')
        if (location.isBlank() || location.startsWith(":memory:") || location.startsWith("file:")) return null
        return BackupSource(url, Path.of(location))
    }

    private fun backUp(
        source: BackupSource,
        firstPendingVersion: String,
    ) {
        val target = source.file.resolveSibling(source.file.fileName.toString() + ".pre-V$firstPendingVersion")
        Files.deleteIfExists(target)
        DriverManager.getConnection(source.url).use { connection ->
            connection.createStatement().use { it.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'") }
        }
        logger.info { "Copied the database to $target before upgrading" }
    }

    private fun legacyVersions(url: String): List<Int> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM $LEGACY_TABLE ORDER BY version").use { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                }
            }
        }

    private fun hasTable(
        url: String,
        name: String,
    ): Boolean =
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { it.next() }
            }
        }
}
