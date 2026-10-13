package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.NotificationService
import dev.koenv.chaptervault.infrastructure.SeriesRefreshScheduler
import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.repositories.BackupRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ChapterRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.NotificationRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.OpdsReadRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.ProgressRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.SeriesRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserRepository
import dev.koenv.chaptervault.infrastructure.database.repositories.UserSeriesStatusRepository
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
import dev.koenv.chaptervault.infrastructure.update.UpdateCheckerService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskReadStore
import dev.koenv.chaptervault.server.generated.BuildConfig
import org.koin.dsl.module
import java.nio.file.Paths

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
        single { BackupRepository() }
        single<BackupApi> { get<BackupRepository>() }
        single { UserSeriesStatusRepository() }
        single<ReadingStatusApi> { get<UserSeriesStatusRepository>() }
        single { OpdsReadRepository() }
        single<OpdsReadApi> { get<OpdsReadRepository>() }
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
        single { UpdateCheckerService(get(), BuildConfig.VERSION) }
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
