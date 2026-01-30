package dev.koenv.chaptervault.database

import dev.koenv.chaptervault.core.config.DatabaseAppConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Supported database types.
 */
enum class DatabaseType {
    H2,
    H2_MEMORY,
    SQLITE,
    POSTGRESQL
}

/**
 * Database configuration options.
 */
data class DatabaseOptions(
    val type: DatabaseType = DatabaseType.SQLITE,
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
     * Initialize database from AppConfig.
     * This is the preferred method when using ConfigurationService.
     */
    fun initialize(dataDir: File, config: DatabaseAppConfig): Database {
        val dbType = parseDbType(config.type)

        val options = DatabaseOptions(
            type = dbType,
            url = buildUrl(config, dataDir, dbType),
            user = config.username,
            password = config.password
        )

        return initialize(dataDir, options)
    }

    /**
     * Build JDBC URL from config.
     */
    private fun buildUrl(config: DatabaseAppConfig, dataDir: File, dbType: DatabaseType): String? {
        // If explicit path is provided, use it
        val dbPath = config.path?.let { File(it) }

        return when (dbType) {
            DatabaseType.H2 -> {
                val file = dbPath ?: File(dataDir, "chaptervault")
                "jdbc:h2:file:${file.absolutePath};DB_CLOSE_DELAY=-1"
            }
            DatabaseType.H2_MEMORY -> "jdbc:h2:mem:chaptervault;DB_CLOSE_DELAY=-1"
            DatabaseType.SQLITE -> {
                val file = dbPath ?: File(dataDir, "chaptervault.db")
                "jdbc:sqlite:${file.absolutePath}"
            }
            DatabaseType.POSTGRESQL -> {
                val host = config.host ?: "localhost"
                val port = config.port ?: 5432
                val name = config.name ?: "chaptervault"
                "jdbc:postgresql://$host:$port/$name"
            }
        }
    }

    /**
     * Parse database type string to enum.
     */
    private fun parseDbType(type: String): DatabaseType {
        return when (type.lowercase()) {
            "h2" -> DatabaseType.H2
            "h2_memory", "h2-memory", "h2mem" -> DatabaseType.H2_MEMORY
            "sqlite" -> DatabaseType.SQLITE
            "postgresql", "postgres" -> DatabaseType.POSTGRESQL
            else -> DatabaseType.SQLITE // Default to SQLite
        }
    }

    /**
     * Initialize database connection based on environment variables or defaults.
     * @deprecated Use initialize(dataDir, config) with ConfigurationService instead.
     */
    @Deprecated("Use initialize(dataDir, config) with ConfigurationService")
    fun initialize(dataDir: File): Database {
        val dbType = System.getenv("CHAPTERVAULT_DB_TYPE")?.let { parseDbType(it) }
            ?: DatabaseType.SQLITE

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
            DatabaseType.H2_MEMORY -> initializeH2Memory(options)
            DatabaseType.SQLITE -> initializeSqlite(dataDir, options)
            DatabaseType.POSTGRESQL -> initializePostgres(options)
        }
    }

    /**
     * Initialize H2 in-memory database.
     */
    private fun initializeH2Memory(options: DatabaseOptions): Database {
        val url = options.url ?: "jdbc:h2:mem:chaptervault;DB_CLOSE_DELAY=-1"

        return Database.connect(
            url = url,
            driver = options.driver ?: "org.h2.Driver"
        )
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
