package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SessionTable
import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
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

    private fun org.jetbrains.exposed.sql.Transaction.dropOrphanedColumns() {
        // chapters.status was renamed to download_status; drop the old column if still present
        try { exec("ALTER TABLE chapters DROP COLUMN status") } catch (_: Exception) {}
        // unique index not created by createMissingTablesAndColumns on existing tables
        exec("CREATE UNIQUE INDEX IF NOT EXISTS chapters_series_external_uq ON chapters (series_id, external_id)")
    }
}
