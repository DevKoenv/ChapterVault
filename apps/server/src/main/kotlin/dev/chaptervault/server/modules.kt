package dev.chaptervault.server

import dev.chaptervault.infrastructure.config.AppConfig
import dev.chaptervault.infrastructure.config.ConfigLoader
import dev.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.chaptervault.infrastructure.network.RateLimiter
import dev.chaptervault.infrastructure.network.createHttpClient
import dev.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.chaptervault.kernel.api.LibraryCommandApi
import dev.chaptervault.kernel.api.LibraryReadApi
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
    // EventBus and ExtensionRegistry implementations will be bound here
    // TODO: bind in-memory implementations once kernel internals are implemented
}

val extensionModule = module {
    // Extensions registered here once implemented
}

val interfacesModule = module {
    // NOTE: EventBus must be bound in kernelModule before this resolves
    single { EventProjectionService(get()) }
}

val allModules = listOf(configModule, infrastructureModule, kernelModule, extensionModule, interfacesModule)
