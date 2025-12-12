package dev.koenv.chaptervault.config

/**
 * Root configuration for the application.
 */
data class Config(
    var server: Server = Server(),
    var logger: Logger = Logger(),
    var database: Database = Database(),
    var network: Network = Network(),
    var features: Features = Features(),
) {
    data class Server(
        var host: String = "0.0.0.0",
        var port: Int = 8080
    )

    data class Database(
        var url: String = "jdbc:sqlite:chaptervault.db",
        var user: String = "",
        var password: String = "",
        var poolSize: Int = 5
    )

    data class Network(
        var timeoutMs: Long = 30000,
        var retryCount: Int = 3,
        var concurrentRequests: Int = 8
    )

    /**
     * Logger configuration data class.
     *
     * @property level Minimum [Level] required to log a message.
     * @property directory Directory path for log files.
     * @property colors Enable ANSI color codes for console output.
     * @property timestampFormat Pattern for formatting timestamps in human-readable logs.
     * @property file Enable or disable file logging.
     */
    data class Logger(
        var level: Level = Level.INFO,
        var directory: String = "logs",
        var colors: Boolean = true,
        var timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
        var file: Boolean = true
    )

    data class Features(
        var cache: Boolean = true,
        var debugTools: Boolean = false
    )
}
