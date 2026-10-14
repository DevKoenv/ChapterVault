package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        val database =
            Database.connect(
                url = withImmediateTransactions(config.url),
                driver = config.driver,
                setupConnection = { conn ->
                    conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                    conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
                    conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
                },
            )
        DatabaseMigrations.migrate(database)
    }

    /**
     * Makes every transaction take SQLite's write lock when it starts (`BEGIN IMMEDIATE`). Transactions here often
     * read and then write; with the default deferred mode two of them overlapping fail at once with SQLITE_BUSY,
     * whereas an immediate transaction waits for the lock for as long as `busy_timeout` allows.
     */
    internal fun withImmediateTransactions(url: String): String =
        when {
            !url.startsWith("jdbc:sqlite:") || "transaction_mode" in url -> url
            "?" in url -> "$url&transaction_mode=IMMEDIATE"
            else -> "$url?transaction_mode=IMMEDIATE"
        }

    suspend fun ping(): Boolean =
        try {
            newSuspendedTransaction(Dispatchers.IO) { exec("SELECT 1") }
            true
        } catch (_: Exception) {
            false
        }
}
