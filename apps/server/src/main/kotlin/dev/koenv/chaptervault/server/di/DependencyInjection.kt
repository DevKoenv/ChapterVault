package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.server.config.ConfigValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private val logger = KotlinLogging.logger {}

object DependencyInjection {
    fun start() {
        startKoin {
            modules(allModules)
        }

        val config = GlobalContext.get().get<AppConfig>()
        ConfigValidator.validate(config)
        DatabaseFactory.init(config.database)
        runBlocking { GlobalContext.get().get<SeriesRepository>().cleanupOrphanedFiles() }

        GlobalContext.get().get<ExtensionLoaderService>().loadAll()

        // register default admin on first boot, silently ignored if admin already exists
        val authApi = GlobalContext.get().get<AuthApi>()
        val adminUser = System.getenv("CHAPTERVAULT_ADMIN_USER") ?: "admin"
        val adminPass = System.getenv("CHAPTERVAULT_ADMIN_PASS") ?: "changeme"
        if (adminPass == "changeme") {
            logger.warn {
                "CHAPTERVAULT_ADMIN_PASS is set to the default value 'changeme'. " +
                    "Change it before exposing this server publicly."
            }
        }
        runBlocking {
            try {
                authApi.register(Credentials(adminUser, adminPass), Role.ADMIN)
            } catch (_: Exception) {
                // best-effort, Conflict is returned as Result.Failure not thrown
            }
        }
    }
}
