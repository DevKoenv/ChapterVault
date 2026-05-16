package dev.chaptervault.infrastructure.database

import dev.chaptervault.infrastructure.config.DatabaseConfig
import dev.chaptervault.infrastructure.database.entities.ChapterTable
import dev.chaptervault.infrastructure.database.entities.SeriesTable
import dev.chaptervault.infrastructure.database.entities.TaskTable
import dev.chaptervault.infrastructure.database.entities.UserTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        Database.connect(
            url = config.url,
            driver = config.driver,
        )
        transaction {
            SchemaUtils.create(SeriesTable, ChapterTable, UserTable, TaskTable)
        }
    }
}
