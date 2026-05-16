package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.network.RateLimiter
import dev.koenv.chaptervault.infrastructure.network.createHttpClient
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.api.impl.AuthApiImpl
import dev.koenv.chaptervault.kernel.api.impl.SystemApiImpl
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
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
    single<EventBus> { InMemoryEventBus() }
    single<ExtensionRegistry> { DefaultExtensionRegistry() }
    single<TaskQueue> { InMemoryTaskQueue() }
    single<SystemApi> { SystemApiImpl(get(), get()) }
    single<AuthApi> { AuthApiImpl() }
}

val extensionModule = module {
}

val interfacesModule = module {
    single { EventProjectionService(get()) } // requires EventBus from kernelModule
}

val allModules = listOf(configModule, infrastructureModule, kernelModule, extensionModule, interfacesModule)
