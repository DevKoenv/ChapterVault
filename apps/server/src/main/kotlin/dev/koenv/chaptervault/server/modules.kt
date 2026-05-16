package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.network.RateLimiter
import dev.koenv.chaptervault.infrastructure.network.createHttpClient
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import org.koin.dsl.module

val configModule = module {
    single<AppConfig> { ConfigLoader.load() }
}

val infrastructureModule = module {
    single { SeriesRepository() }
    single<LibraryReadApi> { get<SeriesRepository>() }
    single<LibraryCommandApi> { get<SeriesRepository>() }
    single { createHttpClient() }
    single { RateLimiter() }
}

val kernelModule = module {
    // TODO: EventBus, ExtensionRegistry, SystemApi, AuthApi
}

val extensionModule = module {
}

val interfacesModule = module {
    single { EventProjectionService(get()) } // requires EventBus from kernelModule
}

val allModules = listOf(configModule, infrastructureModule, kernelModule, extensionModule, interfacesModule)
