package dev.koenv.chaptervault.infrastructure.database

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseMigrations {
    private val log = LoggerFactory.getLogger(DatabaseMigrations::class.java)

    private data class Migration(val version: Int, val name: String, val up: Transaction.() -> Unit)

    private val migrations = listOf(
        Migration(1, "initial_schema") { applyV1() },
    )

    fun migrate() {
        transaction {
            exec("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                )
            """.trimIndent())

            val applied: Set<Int> = exec("SELECT version FROM schema_version") { rs ->
                generateSequence { if (rs.next()) rs.getInt("version") else null }.toSet()
            } ?: emptySet()

            migrations.filter { it.version !in applied }.forEach { migration ->
                log.info("Applying database migration V${migration.version}: ${migration.name}")
                migration.up(this)
                exec("INSERT INTO schema_version (version, name, applied_at) VALUES (${migration.version}, '${migration.name}', datetime('now'))")
                log.info("Database migration V${migration.version} applied")
            }
        }
    }

    private fun Transaction.applyV1() {
        exec("""
            CREATE TABLE IF NOT EXISTS users (
                id TEXT NOT NULL,
                username TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                roles TEXT NOT NULL,
                created_at TEXT NOT NULL,
                CONSTRAINT pk_users PRIMARY KEY (id)
            )
        """.trimIndent())
        exec("""
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES users(id),
                token TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                CONSTRAINT pk_sessions PRIMARY KEY (id)
            )
        """.trimIndent())
        exec("""
            CREATE TABLE IF NOT EXISTS series (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                connector_id TEXT NOT NULL,
                external_id TEXT NOT NULL,
                status TEXT NOT NULL,
                auto_download INTEGER NOT NULL DEFAULT 0,
                default_format TEXT,
                cover_url TEXT,
                description TEXT,
                language TEXT NOT NULL DEFAULT '',
                added_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                CONSTRAINT pk_series PRIMARY KEY (id)
            )
        """.trimIndent())
        exec("""
            CREATE TABLE IF NOT EXISTS chapters (
                id TEXT NOT NULL,
                series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                chapter_index REAL NOT NULL,
                external_id TEXT NOT NULL,
                download_status TEXT NOT NULL DEFAULT 'AVAILABLE',
                format TEXT,
                page_count INTEGER,
                added_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                CONSTRAINT pk_chapters PRIMARY KEY (id)
            )
        """.trimIndent())
        exec("""
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
        """.trimIndent())
        exec("""
            CREATE TABLE IF NOT EXISTS progress (
                user_id TEXT NOT NULL REFERENCES users(id),
                chapter_id TEXT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
                read_at TEXT NOT NULL,
                CONSTRAINT pk_progress PRIMARY KEY (user_id, chapter_id)
            )
        """.trimIndent())
        exec("""
            CREATE TABLE IF NOT EXISTS bookmarks (
                id TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES users(id),
                chapter_id TEXT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
                page INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                CONSTRAINT pk_bookmarks PRIMARY KEY (id)
            )
        """.trimIndent())

        exec("CREATE UNIQUE INDEX IF NOT EXISTS users_username_uq ON users (username)")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS sessions_token_uq ON sessions (token)")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS chapters_series_external_uq ON chapters (series_id, external_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_chapters_series_id ON chapters (series_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_bookmarks_user_id ON bookmarks (user_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_bookmarks_chapter_id ON bookmarks (chapter_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions (user_id)")

        // Drop legacy column from pre-V1 rename (chapters.status → chapters.download_status)
        try { exec("ALTER TABLE chapters DROP COLUMN status") } catch (_: Exception) {}
    }
}
