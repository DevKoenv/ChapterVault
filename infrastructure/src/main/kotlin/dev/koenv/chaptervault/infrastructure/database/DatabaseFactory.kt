package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        Database.connect(
            url = config.url,
            driver = config.driver,
            setupConnection = { it.createStatement().execute("PRAGMA foreign_keys = ON") },
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(UserTable, SessionTable, SeriesTable, ChapterTable, TaskTable, ProgressTable, BookmarkTable)
            dropOrphanedColumns()
        }
    }

    suspend fun ping(): Boolean = try {
        newSuspendedTransaction(Dispatchers.IO) { exec("SELECT 1") }
        true
    } catch (_: Exception) {
        false
    }

    private fun org.jetbrains.exposed.sql.Transaction.dropOrphanedColumns() {
        // chapters.status was renamed to download_status; drop the old column if still present
        try { exec("ALTER TABLE chapters DROP COLUMN status") } catch (_: Exception) {}
        // unique index not created by createMissingTablesAndColumns on existing tables
        exec("CREATE UNIQUE INDEX IF NOT EXISTS chapters_series_external_uq ON chapters (series_id, external_id)")
        // FK indices for query performance
        exec("CREATE INDEX IF NOT EXISTS idx_chapters_series_id ON chapters (series_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_bookmarks_user_id ON bookmarks (user_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_bookmarks_chapter_id ON bookmarks (chapter_id)")
        exec("CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions (user_id)")
    }
}
