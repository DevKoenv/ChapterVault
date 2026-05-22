package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import dev.koenv.chaptervault.infrastructure.database.repositories.BookmarkRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ProgressRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserRepository
import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.extensions.connectors.sources.CustomConnector
import dev.koenv.chaptervault.extensions.connectors.sources.MockConnector
import dev.koenv.chaptervault.extensions.connectors.sources.mangadex.MangaDexConnector
import dev.koenv.chaptervault.infrastructure.network.createHttpClient
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.CbzWriter
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.infrastructure.storage.FolderWriter
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BookmarkApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.api.impl.SystemApiImpl
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.EventPublishingTaskQueue
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskReadStore
import org.koin.dsl.module
import java.nio.file.Paths

val configModule = module {
    single<AppConfig> { ConfigLoader.load() }
}

val infrastructureModule = module {
    single { SeriesRepository(get()) }
    single<LibraryReadApi> { get<SeriesRepository>() }
    single<LibraryCommandApi> { get<SeriesRepository>() }
    single { UserRepository() }
    single<AuthApi> { get<UserRepository>() }
    single { TaskRepository() }
    single<TaskReadStore> { get<TaskRepository>() }
    single { ChapterRepository() }
    single { ProgressRepository() }
    single<ProgressApi> { get<ProgressRepository>() }
    single { BookmarkRepository() }
    single<BookmarkApi> { get<BookmarkRepository>() }
    single { createHttpClient() }
    single { CbzWriter() }
    single { FolderWriter() }
    single { ArchiveWriterSelector(listOf(get<CbzWriter>(), get<FolderWriter>())) }
    single { FileStorage(Paths.get(get<AppConfig>().storage.basePath), get()) }
    single {
        TaskExecutorService(
            taskQueue = get(),
            taskRepository = get(),
            connectorRegistry = get(),
            seriesRepository = get(),
            chapterRepository = get(),
            fileStorage = get(),
            httpClient = get(),
            eventBus = get(),
        )
    }
}

val kernelModule = module {
    single<EventBus> { InMemoryEventBus() }
    single<ExtensionRegistry> { DefaultExtensionRegistry() }
    single<TaskQueue> { EventPublishingTaskQueue(InMemoryTaskQueue(), get()) }
    single<SystemApi> { SystemApiImpl(get(), get(), get(), get()) }
}

val extensionModule = module {
    single<ConnectorRegistry> { DefaultConnectorRegistry() }
    single { MockConnector() }
    single { CustomConnector(get()) }
    single { MangaDexConnector(get()) }
}

val interfacesModule = module {
    single { EventProjectionService(get()) }
}

val allModules = listOf(configModule, infrastructureModule, kernelModule, extensionModule, interfacesModule)
