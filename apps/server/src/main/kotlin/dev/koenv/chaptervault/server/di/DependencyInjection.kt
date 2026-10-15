package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.server.auth.AdminBootstrap
import dev.koenv.chaptervault.server.auth.PasswordResetFile
import dev.koenv.chaptervault.server.config.ConfigValidator
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.nio.file.Paths

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

        runBlocking {
            val auth = GlobalContext.get().get<AuthApi>()
            AdminBootstrap(auth).run()
            PasswordResetFile(auth, Paths.get(config.dataDir, "reset-password")).run()
        }
    }
}
