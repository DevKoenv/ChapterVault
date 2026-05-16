package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.java.KoinJavaComponent.getKoin

fun main() {
    DependencyInjection.start()
    val config = getKoin().get<AppConfig>()
    DatabaseFactory.init(config.database)
    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        bootstrap()
    }.start(wait = true)
}
