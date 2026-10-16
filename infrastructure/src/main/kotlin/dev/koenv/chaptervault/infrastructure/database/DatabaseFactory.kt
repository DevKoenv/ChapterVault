package dev.koenv.chaptervault.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private const val SQLITE_URL_PREFIX = "jdbc:sqlite:"

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun init(config: DatabaseConfig) {
        close()
        val ds = buildDataSource(config)
        dataSource = ds
        val database = Database.connect(ds)
        DatabaseMigrations.migrate(database, config.username, config.password)
    }

    /** Releases the connection pool. Safe to call when nothing was initialized, and more than once. */
    fun close() {
        dataSource?.close()
        dataSource = null
    }

    private fun buildDataSource(config: DatabaseConfig): HikariDataSource {
        val isSqlite = config.url.startsWith(SQLITE_URL_PREFIX)
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = if (isSqlite) withImmediateTransactions(config.url) else config.url
                driverClassName = config.driver
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                // Hikari defaults minimumIdle to maximumPoolSize, which opens every pooled connection at once
                // on every startup; a crash-restart loop then multiplies that burst by however many restarts
                // happen before the loop is stopped. Keep startup down to (at most) one connection instead.
                minimumIdle = minOf(1, config.maxPoolSize)
                // SQLite allows exactly one writer at a time; PRAGMAs below plus busy_timeout make
                // concurrent transactions wait for each other instead of failing with SQLITE_BUSY.
                if (isSqlite) {
                    connectionInitSql = "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000; PRAGMA journal_mode = WAL"
                } else {
                    // proactively pings idle connections so a silently dropped network path (e.g. a VPN hiccup)
                    // is noticed and the connection replaced before the app tries to use it, rather than only
                    // on the next real query
                    keepaliveTime = 2 * 60 * 1000
                }
            }
        return HikariDataSource(hikariConfig)
    }

    /**
     * Makes every transaction take SQLite's write lock when it starts (`BEGIN IMMEDIATE`). Transactions here often
     * read and then write; with the default deferred mode two of them overlapping fail at once with SQLITE_BUSY,
     * whereas an immediate transaction waits for the lock for as long as `busy_timeout` allows.
     */
    internal fun withImmediateTransactions(url: String): String =
        when {
            !url.startsWith(SQLITE_URL_PREFIX) || "transaction_mode" in url -> url
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
