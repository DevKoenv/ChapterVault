package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.connectors.CustomConnector
import dev.koenv.chaptervault.infrastructure.connectors.DefaultConnectorRegistry
import dev.koenv.chaptervault.infrastructure.connectors.MockConnector
import dev.koenv.chaptervault.infrastructure.connectors.mangadex.MangaDexConnector
import dev.koenv.chaptervault.infrastructure.enricher.DefaultMetadataEnricherRegistry
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExternalExtensionLoader
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionManager
import dev.koenv.chaptervault.kernel.extension.MetadataEnricherRegistry
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import dev.koenv.chaptervault.server.extensions.DefaultExtensionContext
import dev.koenv.chaptervault.server.extensions.DefaultManagedConfig
import dev.koenv.chaptervault.server.generated.BuildConfig
import org.koin.dsl.module
import java.nio.file.Path
import java.nio.file.Paths

val extensionModule =
    module {
        single<ConnectorRegistry> { DefaultConnectorRegistry() }
        single<MetadataEnricherRegistry> { DefaultMetadataEnricherRegistry() }
        single {
            val config = get<AppConfig>()
            val extensionsDataRoot = Paths.get(config.storage.libraryPath).parent.resolve("extensions")
            val connectorRegistry = get<ConnectorRegistry>()
            val enricherRegistry = get<MetadataEnricherRegistry>()
            val contextFactory: (String, Path) -> ExtensionContext = { _, dir ->
                DefaultExtensionContext(
                    httpClient = get(),
                    library = get(),
                    progress = get(),
                    system = get(),
                    connectorRegistry = connectorRegistry,
                    enricherRegistry = enricherRegistry,
                    notificationRegistry = get<NotificationChannelRegistry>(),
                    dataDir = dir,
                    config = DefaultManagedConfig(dir.resolve("config.yml").toFile()),
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
                        serverVersion = BuildConfig.VERSION,
                    ),
                bundledExtensions = emptyList(),
            )
        }
        single<ExtensionManager> { get<ExtensionLoaderService>() }
    }
