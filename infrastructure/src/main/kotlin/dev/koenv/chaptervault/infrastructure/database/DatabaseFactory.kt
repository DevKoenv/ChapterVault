package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        Database.connect(
            url = config.url,
            driver = config.driver,
            setupConnection = { conn ->
                conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
                conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            },
        )
        DatabaseMigrations.migrate()
    }

    suspend fun ping(): Boolean = try {
        newSuspendedTransaction(Dispatchers.IO) { exec("SELECT 1") }
        true
    } catch (_: Exception) {
        false
    }
}
