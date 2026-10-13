package dev.koenv.chaptervault.infrastructure.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

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

    internal val migrations: List<Migration> =
        listOf(
            Migration(1, "initial_schema", v1Statements()),
            Migration(2, "editable_metadata_fields", v2Statements()),
        )

    fun migrate(db: Database) {
        transaction(db) { runMigrations() }
    }

    fun migrate() {
        transaction { runMigrations() }
    }

    private fun org.jetbrains.exposed.sql.Transaction.runMigrations() {
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

        migrations.filter { it.version !in applied }.forEach { migration ->
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

private fun v1Statements(): List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS users (
            id TEXT NOT NULL,
            username TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            roles TEXT NOT NULL,
            created_at TEXT NOT NULL,
            CONSTRAINT pk_users PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT NOT NULL,
            user_id TEXT NOT NULL REFERENCES users(id),
            token TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            created_at TEXT NOT NULL,
            CONSTRAINT pk_sessions PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS series (
            id TEXT NOT NULL,
            title TEXT NOT NULL,
            connector_id TEXT,
            external_id TEXT,
            status TEXT NOT NULL,
            auto_download INTEGER NOT NULL DEFAULT 0,
            default_format TEXT,
            cover_url TEXT,
            description TEXT,
            language TEXT NOT NULL DEFAULT 'en',
            author TEXT,
            artist TEXT,
            year INTEGER,
            upstream_status TEXT,
            added_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            CONSTRAINT pk_series PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS chapters (
            id TEXT NOT NULL,
            series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
            title TEXT NOT NULL,
            chapter_index REAL NOT NULL,
            external_id TEXT,
            download_status TEXT NOT NULL DEFAULT 'AVAILABLE',
            format TEXT,
            page_count INTEGER,
            added_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            CONSTRAINT pk_chapters PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS tasks (
            id TEXT NOT NULL,
            type TEXT NOT NULL,
            status TEXT NOT NULL,
            target_type TEXT NOT NULL,
            target_id TEXT NOT NULL,
            payload TEXT NOT NULL DEFAULT '{}',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            error_message TEXT,
            retry_count INTEGER NOT NULL DEFAULT 0,
            CONSTRAINT pk_tasks PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS user_series_status (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
            status TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            CONSTRAINT pk_user_series_status PRIMARY KEY (user_id, series_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS notification_targets (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            type TEXT NOT NULL,
            url TEXT NOT NULL,
            token TEXT,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            CONSTRAINT pk_notification_targets PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS series_genres (
            series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
            genre TEXT NOT NULL,
            CONSTRAINT pk_series_genres PRIMARY KEY (series_id, genre)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS user_series_progress (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
            latest_chapter_id TEXT REFERENCES chapters(id) ON DELETE SET NULL,
            latest_page INTEGER NOT NULL DEFAULT 0,
            read_at TEXT NOT NULL,
            CONSTRAINT pk_user_series_progress PRIMARY KEY (user_id, series_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS series_overrides (
            series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
            field TEXT NOT NULL,
            value TEXT NOT NULL,
            CONSTRAINT pk_series_overrides PRIMARY KEY (series_id, field)
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS users_username_uq ON users (username)",
        "CREATE UNIQUE INDEX IF NOT EXISTS sessions_token_uq ON sessions (token)",
        "CREATE UNIQUE INDEX IF NOT EXISTS chapters_series_external_uq ON chapters (series_id, external_id) WHERE external_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_chapters_series_id ON chapters (series_id)",
        "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions (user_id)",
        "CREATE INDEX IF NOT EXISTS idx_uss_user_id ON user_series_status (user_id)",
        "CREATE INDEX IF NOT EXISTS idx_series_genres_series_id ON series_genres (series_id)",
        "CREATE INDEX IF NOT EXISTS idx_usp_user_id ON user_series_progress (user_id)",
        "CREATE INDEX IF NOT EXISTS idx_series_overrides_series_id ON series_overrides (series_id)",
    )

private fun v2Statements(): List<String> =
    listOf(
        "ALTER TABLE series ADD COLUMN user_edited_fields TEXT NOT NULL DEFAULT ''",
        """
        UPDATE series SET
          title = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'title'), title),
          cover_url = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'coverUrl'), cover_url),
          description = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'description'), description),
          author = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'author'), author),
          artist = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'artist'), artist),
          upstream_status = COALESCE((SELECT value FROM series_overrides WHERE series_id = series.id AND field = 'upstreamStatus'), upstream_status),
          user_edited_fields = COALESCE(
            (SELECT GROUP_CONCAT(field, ',') FROM series_overrides
             WHERE series_id = series.id AND field IN ('title', 'coverUrl', 'description', 'author', 'artist', 'upstreamStatus')),
            ''
          )
        WHERE EXISTS (SELECT 1 FROM series_overrides WHERE series_id = series.id)
        """.trimIndent(),
        "DROP TABLE IF EXISTS series_overrides",
    )
