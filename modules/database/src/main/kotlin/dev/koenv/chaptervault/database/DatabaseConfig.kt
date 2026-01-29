package dev.koenv.chaptervault.database

import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Supported database types.
 */
enum class DatabaseType {
    H2,
    SQLITE,
    POSTGRESQL
}

/**
 * Database configuration options.
 */
data class DatabaseOptions(
    val type: DatabaseType = DatabaseType.H2,
    val url: String? = null,
    val driver: String? = null,
    val user: String? = null,
    val password: String? = null
)

/**
 * Database configuration and initialization.
 * Supports multiple database backends: H2, SQLite, PostgreSQL.
 */
object DatabaseConfig {

    /**
     * Initialize database connection based on environment variables or defaults.
     *
     * Environment variables:
     * - CHAPTERVAULT_DB_TYPE: h2, sqlite, postgres (default: h2)
     * - CHAPTERVAULT_DB_URL: Full JDBC URL (optional, auto-generated if not set)
     * - CHAPTERVAULT_DB_USER: Database username (for postgres)
     * - CHAPTERVAULT_DB_PASSWORD: Database password (for postgres)
     */
    fun initialize(dataDir: File): Database {
        val dbType = System.getenv("CHAPTERVAULT_DB_TYPE")?.uppercase()?.let {
            try {
                DatabaseType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                DatabaseType.H2
            }
        } ?: DatabaseType.H2

        val options = DatabaseOptions(
            type = dbType,
            url = System.getenv("CHAPTERVAULT_DB_URL"),
            user = System.getenv("CHAPTERVAULT_DB_USER"),
            password = System.getenv("CHAPTERVAULT_DB_PASSWORD")
        )

        return initialize(dataDir, options)
    }

    /**
     * Initialize database with specific options.
     */
    fun initialize(dataDir: File, options: DatabaseOptions): Database {
        dataDir.mkdirs()

        return when (options.type) {
            DatabaseType.H2 -> initializeH2(dataDir, options)
            DatabaseType.SQLITE -> initializeSqlite(dataDir, options)
            DatabaseType.POSTGRESQL -> initializePostgres(options)
        }
    }

    /**
     * Initialize H2 file-based database.
     */
    private fun initializeH2(dataDir: File, options: DatabaseOptions): Database {
        val url = options.url ?: run {
            val dbFile = File(dataDir, "chaptervault")
            "jdbc:h2:file:${dbFile.absolutePath};DB_CLOSE_DELAY=-1"
        }

        return Database.connect(
            url = url,
            driver = options.driver ?: "org.h2.Driver"
        )
    }

    /**
     * Initialize SQLite file-based database.
     */
    private fun initializeSqlite(dataDir: File, options: DatabaseOptions): Database {
        val url = options.url ?: run {
            val dbFile = File(dataDir, "chaptervault.db")
            "jdbc:sqlite:${dbFile.absolutePath}"
        }

        return Database.connect(
            url = url,
            driver = options.driver ?: "org.sqlite.JDBC"
        )
    }

    /**
     * Initialize PostgreSQL database.
     */
    private fun initializePostgres(options: DatabaseOptions): Database {
        val url = options.url
            ?: throw IllegalArgumentException("CHAPTERVAULT_DB_URL is required for PostgreSQL")

        return Database.connect(
            url = url,
            driver = options.driver ?: "org.postgresql.Driver",
            user = options.user ?: "",
            password = options.password ?: ""
        )
    }

    /**
     * Initialize in-memory H2 database for testing.
     */
    fun initializeInMemory(): Database {
        return Database.connect(
            url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
    }

    /**
     * Initialize in-memory SQLite database for testing.
     */
    fun initializeInMemorySqlite(): Database {
        return Database.connect(
            url = "jdbc:sqlite::memory:",
            driver = "org.sqlite.JDBC"
        )
    }
}
