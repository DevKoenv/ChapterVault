package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.server.di.DependencyInjection
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.java.KoinJavaComponent.getKoin

fun main() {
    // DependencyInjection.start() already validated the config and connected and migrated the database
    DependencyInjection.start()
    val config = getKoin().get<AppConfig>()
    try {
        embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            bootstrap()
        }.start(wait = true)
    } finally {
        // start(wait = true) only returns once the server has actually stopped taking requests
        DatabaseFactory.close()
    }
}
