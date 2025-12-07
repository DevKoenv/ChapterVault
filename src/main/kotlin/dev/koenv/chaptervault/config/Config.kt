package dev.koenv.chaptervault.config

import dev.koenv.chaptervault.core.Logger

/**
 * Root configuration for the application.
 */
data class Config(
    var server: Server = Server(),
    var logger: Logger.Config = Logger.Config(),
    var database: Database = Database(),
    var network: Network = Network(),
    var features: Features = Features()
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

    data class Features(
        var cache: Boolean = true,
        var debugTools: Boolean = false
    )
}
