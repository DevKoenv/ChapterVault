package dev.koenv.chaptervault.infrastructure.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

private const val MIGRATION_FOLDER = "db/migration"
private val MIGRATION_FILE = Regex("^V(\\d+)__(.+)\\.sql$")
private const val SQLITE_URL_PREFIX = "jdbc:sqlite:"

/**
 * Applies the numbered `.sql` files in `resources/db/migration` (listed in `index.txt`) that the database has not seen.
 *
 * Every applied migration's checksum is verified on startup, and all pending migrations run in one transaction, so a
 * failure leaves the schema as it was. Before changing an existing database it is copied to `<file>.pre-V<N>` with
 * `VACUUM INTO`, which also gives a consistent copy of a database in WAL mode.
 */
object DatabaseMigrations {
    internal data class Migration(
        val version: Int,
        val name: String,
        val statements: List<String>,
    ) {
        val checksum: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            statements.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    internal val migrations: List<Migration> by lazy { loadFromClasspath() }

    fun migrate(db: Database) = migrate(db, migrations)

    internal fun migrate(
        db: Database,
        migrations: List<Migration>,
    ) {
        transaction(db) { runMigrations(migrations, backupSource(db.url)) }
    }

    private fun loadFromClasspath(): List<Migration> {
        val loader = DatabaseMigrations::class.java.classLoader
        val index =
            loader.getResourceAsStream("$MIGRATION_FOLDER/index.txt")?.bufferedReader()?.readText()
                ?: error("Missing $MIGRATION_FOLDER/index.txt")
        val loaded =
            index.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { file ->
                val match = MIGRATION_FILE.matchEntire(file) ?: error("Migration file name '$file' must look like V1__name.sql")
                val sql =
                    loader.getResourceAsStream("$MIGRATION_FOLDER/$file")?.bufferedReader()?.readText()
                        ?: error("Migration '$file' is listed in index.txt but missing")
                Migration(match.groupValues[1].toInt(), match.groupValues[2], SqlScript.split(sql))
            }
        check(loaded.map { it.version } == (1..loaded.size).toList()) {
            "Migration versions must be 1..${loaded.size} without gaps, in order: ${loaded.map { it.version }}"
        }
        return loaded
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
        firstPendingVersion: Int,
    ) {
        val target = source.file.resolveSibling(source.file.fileName.toString() + ".pre-V$firstPendingVersion")
        Files.deleteIfExists(target)
        DriverManager.getConnection(source.url).use { connection ->
            connection.createStatement().use { it.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'") }
        }
        logger.info { "Copied the database to $target before upgrading" }
    }

    private fun org.jetbrains.exposed.sql.Transaction.runMigrations(
        migrations: List<Migration>,
        backupSource: BackupSource?,
    ) {
        exec(
            """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL,
                checksum TEXT
            )
            """.trimIndent(),
        )

        val applied: Map<Int, String?> =
            exec(
                "SELECT version, checksum FROM schema_version",
            ) { rs ->
                buildMap {
                    while (rs.next()) put(rs.getInt("version"), rs.getString("checksum"))
                }
            } ?: emptyMap()

        migrations.filter { it.version in applied }.forEach { migration ->
            val stored = applied[migration.version]
            if (stored != null && stored != migration.checksum) {
                error(
                    "Migration V${migration.version} ('${migration.name}') checksum mismatch: " +
                        "stored=$stored computed=${migration.checksum}. " +
                        "Never modify a migration after it has been applied.",
                )
            }
        }

        val pending = migrations.filter { it.version !in applied }
        if (pending.isNotEmpty() && applied.isNotEmpty() && backupSource != null) {
            // done through a second connection: VACUUM cannot run inside this transaction
            backUp(backupSource, pending.first().version)
        }

        pending.forEach { migration ->
            logger.info { "Applying migration V${migration.version}: ${migration.name}" }
            migration.statements.forEach { exec(it) }
            exec(
                "INSERT INTO schema_version (version, name, applied_at, checksum) VALUES (?, ?, datetime('now'), ?)",
                listOf(
                    IntegerColumnType() to migration.version,
                    TextColumnType() to migration.name,
                    TextColumnType() to migration.checksum,
                ),
            )
            logger.info { "Migration V${migration.version} applied, checksum: ${migration.checksum}" }
        }
    }
}
