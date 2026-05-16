package dev.chaptervault.server

import dev.chaptervault.infrastructure.config.AppConfig
import dev.chaptervault.infrastructure.database.DatabaseFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.java.KoinJavaComponent.getKoin

fun main() {
    // 1. Load config and start DI
    DependencyInjection.start()

    // 2. Init database
    val config = getKoin().get<AppConfig>()
    DatabaseFactory.init(config.database)

    // 3. Start Ktor server
    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        bootstrap()
    }.start(wait = true)
}
