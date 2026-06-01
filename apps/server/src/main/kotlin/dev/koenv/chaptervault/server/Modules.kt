package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.NotificationService
import dev.koenv.chaptervault.infrastructure.PersistingTaskQueue
import dev.koenv.chaptervault.infrastructure.SeriesRefreshScheduler
import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import dev.koenv.chaptervault.infrastructure.connectors.CustomConnector
import dev.koenv.chaptervault.infrastructure.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.infrastructure.connectors.MockConnector
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.MangaDexConnector
import dev.koenv.chaptervault.infrastructure.database.repositories.BookmarkRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionConfigRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ExtensionRegistryRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.NotificationRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ProgressRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserSeriesStatusRepository
import dev.koenv.chaptervault.infrastructure.enricher.DefaultMetadataEnricherRegistry
import dev.koenv.chaptervault.infrastructure.extensions.ExtensionRegistryClient
import dev.koenv.chaptervault.infrastructure.extensions.ExtensionRegistryService
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExternalExtensionLoader
import dev.koenv.chaptervault.infrastructure.network.createHttpClient
import dev.koenv.chaptervault.infrastructure.notifications.DefaultNotificationChannelRegistry
import dev.koenv.chaptervault.infrastructure.notifications.DiscordNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.GotifyNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.NtfyNotificationChannel
import dev.koenv.chaptervault.infrastructure.notifications.WebhookNotificationChannel
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.CbzWriter
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.infrastructure.storage.FolderWriter
import dev.koenv.chaptervault.infrastructure.storage.JpegThumbnailFormat
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BookmarkApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.api.impl.SystemApiImpl
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.runtime.EventPublishingTaskQueue
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskReadStore
import org.koin.dsl.module
import java.nio.file.Path
import java.nio.file.Paths

val configModule =
    module {
        single<AppConfig> { ConfigLoader.load() }
    }

val infrastructureModule =
    module {
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
        single { UserSeriesStatusRepository() }
        single<ReadingStatusApi> { get<UserSeriesStatusRepository>() }
        single { NotificationRepository() }
        single<NotificationApi> { get<NotificationRepository>() }
        single { NtfyNotificationChannel(get()) }
        single { GotifyNotificationChannel(get()) }
        single { DiscordNotificationChannel(get()) }
        single { WebhookNotificationChannel(get()) }
        single<NotificationChannelRegistry> {
            DefaultNotificationChannelRegistry().also { registry ->
                registry.register(get<NtfyNotificationChannel>())
                registry.register(get<GotifyNotificationChannel>())
                registry.register(get<DiscordNotificationChannel>())
                registry.register(get<WebhookNotificationChannel>())
            }
        }
        single { NotificationService(get(), get(), get()) }
        single<NotificationDispatchApi> { get<NotificationService>() }
        single { createHttpClient() }
        single { CbzWriter() }
        single { FolderWriter() }
        single { ArchiveWriterSelector(listOf(get<CbzWriter>(), get<FolderWriter>())) }
        single {
            FileStorage(
                libraryPath = Paths.get(get<AppConfig>().storage.libraryPath),
                thumbnailsPath = Paths.get(get<AppConfig>().storage.thumbnailsPath),
                writerSelector = get(),
                thumbnailFormat = JpegThumbnailFormat,
            )
        }
        single { ExtensionConfigRepository() }
        single { ExtensionRegistryRepository() }
        single { SeriesRefreshScheduler(get(), get(), get<AppConfig>().refresh.intervalHours) }
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
                enricherRegistry = get(),
            )
        }
    }

val kernelModule =
    module {
        single<EventBus> { InMemoryEventBus() }
        single<ExtensionRegistry> { DefaultExtensionRegistry() }
        single<TaskQueue> { PersistingTaskQueue(EventPublishingTaskQueue(InMemoryTaskQueue(), get()), get()) }
        single<SystemApi> { SystemApiImpl(get(), get(), get(), get()) }
    }

val extensionModule =
    module {
        single<ConnectorRegistry> { DefaultConnectorRegistry() }
        single<MetadataEnricherRegistry> { DefaultMetadataEnricherRegistry() }
        single {
            val config = get<AppConfig>()
            val extensionsDataRoot = Paths.get(config.storage.libraryPath).parent.resolve("extensions")
            val connectorRegistry = get<ConnectorRegistry>()
            val enricherRegistry = get<MetadataEnricherRegistry>()
            val contextFactory: (String, Path) -> ExtensionContext = { extensionId, dir ->
                DefaultExtensionContext(
                    httpClient = get(),
                    library = get(),
                    progress = get(),
                    system = get(),
                    connectorRegistry = connectorRegistry,
                    enricherRegistry = enricherRegistry,
                    notificationRegistry = get<NotificationChannelRegistry>(),
                    config = get<ExtensionConfigRepository>().forExtension(extensionId),
                    dataDir = dir,
                )
            }
            // Register built-in connectors directly; they are first-party and do not need the extension adapter.
            connectorRegistry.register(MangaDexConnector(get()))
            if (config.debug.mockConnectorEnabled) {
                connectorRegistry.register(MockConnector())
                connectorRegistry.register(CustomConnector(get()))
            }
            ExtensionLoaderService(
                extensionRegistry = get(),
                connectorRegistryDelegate = connectorRegistry,
                enricherRegistryDelegate = enricherRegistry,
                notificationRegistryDelegate = get(),
                contextFactory = contextFactory,
                externalLoader =
                    ExternalExtensionLoader(
                        extensionsDir = extensionsDataRoot,
                        serverVersion = BuildInfo.SERVER_VERSION,
                    ),
                bundledExtensions = emptyList(),
            )
        }
        single<ExtensionManager> { get<ExtensionLoaderService>() }
    }

val interfacesModule =
    module {
        single { EventProjectionService(get()) }
    }

val registryModule =
    module {
        single { ExtensionRegistryClient(get()) }
        single {
            ExtensionRegistryService(
                registryRepo = get(),
                registryClient = get(),
                extensionManager = get<ExtensionManager>(),
                httpClient = get(),
            )
        }
    }

val allModules = listOf(configModule, infrastructureModule, kernelModule, extensionModule, interfacesModule, registryModule)
