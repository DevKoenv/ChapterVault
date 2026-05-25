package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.sources.CustomConnector
import dev.koenv.chaptervault.extensions.connectors.sources.MockConnector
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.MangaDexConnector
import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

object DependencyInjection {
    fun start() {
        startKoin {
            modules(allModules)
        }

        val config = GlobalContext.get().get<AppConfig>()
        ConfigValidator.validate(config)
        DatabaseFactory.init(config.database)
        runBlocking { GlobalContext.get().get<SeriesRepository>().cleanupOrphanedFiles() }

        // Register connectors post-boot (after all Koin bindings are resolved)
        val connectorRegistry = GlobalContext.get().get<ConnectorRegistry>()
        if (config.debug.mockConnectorEnabled) connectorRegistry.register(GlobalContext.get().get<MockConnector>())
        connectorRegistry.register(GlobalContext.get().get<CustomConnector>())
        connectorRegistry.register(GlobalContext.get().get<MangaDexConnector>())

        // Register default admin on first boot; silently ignored if admin already exists
        val authApi = GlobalContext.get().get<AuthApi>()
        val adminUser = System.getenv("CHAPTERVAULT_ADMIN_USER") ?: "admin"
        val adminPass = System.getenv("CHAPTERVAULT_ADMIN_PASS") ?: "changeme"
        runBlocking {
            try {
                authApi.register(Credentials(adminUser, adminPass), Role.ADMIN)
            } catch (_: Exception) {
                // best-effort; Conflict is returned as Result.Failure, not thrown
            }
        }
    }
}
