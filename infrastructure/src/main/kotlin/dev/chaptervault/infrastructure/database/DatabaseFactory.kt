package dev.chaptervault.infrastructure.database

import dev.chaptervault.infrastructure.config.DatabaseConfig
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
            // TODO: apply migrations / create tables here
        }
    }
}
