package dev.koenv.chaptervault.app

import dev.koenv.chaptervault.api.ApiConfiguration
import dev.koenv.chaptervault.api.configureApi
import dev.koenv.chaptervault.connectors.impl.MockConnector
import dev.koenv.chaptervault.connectors.impl.SampleConnector
import dev.koenv.chaptervault.connectors.registry.ConnectorRegistryImpl
import dev.koenv.chaptervault.core.BuildConfig
import dev.koenv.chaptervault.database.DatabaseConfig
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.DownloadTaskRepository
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.opds.OpdsConfiguration
import dev.koenv.chaptervault.opds.configureOpds
import dev.koenv.chaptervault.orchestration.config.ConfigurationServiceImpl
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import dev.koenv.chaptervault.orchestration.execution.LocalExecutor
import dev.koenv.chaptervault.orchestration.fetch.FetchClientImpl
import dev.koenv.chaptervault.storage.impl.FileStorageSink
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Main application entry point.
 * Bootstraps all modules and starts the API server.
 */
fun main() {
    logger.info { "${BuildConfig.APP_NAME} v${BuildConfig.VERSION} starting..." }
    logger.info { "Environment: ${if (BuildConfig.isDevelopment) "development" else "production"}" }

    // Initialize configuration service
    val configService = ConfigurationServiceImpl.create()
    val appConfig = configService.getAppConfig()

    if (configService.configExists()) {
        logger.info { "Configuration loaded from: ${configService.getConfigPath()}" }
    } else {
        logger.info { "No configuration file found, using defaults" }
    }

    // Resolve data directory (defaults are in ConfigurationService)
    val dataDir = File(appConfig.dataPath)
    dataDir.mkdirs()
    logger.info { "Data directory: ${dataDir.absolutePath}" }

    // Initialize database using configuration
    logger.info { "Database type: ${appConfig.database.type}" }
    val database = DatabaseConfig.initialize(dataDir, appConfig.database)
    logger.info { "Database initialized" }

    // Initialize repositories
    val seriesRepository = SeriesRepository(database).also { it.initialize() }
    val chapterRepository = ChapterRepository(database).also { it.initialize() }
    val downloadTaskRepository = DownloadTaskRepository(database).also { it.initialize() }
    logger.info { "Repositories initialized" }

    // Resolve storage directory (defaults are in ConfigurationService)
    val storageDir = File(appConfig.storage.path)
    storageDir.mkdirs()
    logger.info { "Storage directory: ${storageDir.absolutePath}" }
    val storageSink = FileStorageSink(storageDir, appConfig.storage.minFreeSpaceMb)

    // Initialize HTTP client and executor for connectors
    val httpConfig = configService.getHttpConfig()
    val fetchClient = FetchClientImpl(httpConfig)
    val executor = LocalExecutor(fetchClient)
    logger.info { "Executor initialized (User-Agent: ${httpConfig.userAgent})" }

    // Initialize connector registry
    val connectorRegistry = ConnectorRegistryImpl()

    // Register connectors
    // In development mode, register mock connectors for testing
    if (BuildConfig.isDevelopment) {
        logger.info { "Development mode: registering mock connectors" }
        connectorRegistry.register(MockConnector(executor))
        connectorRegistry.register(SampleConnector(executor))
    }

    // TODO: Register production connectors here
    // See docs/LEGAL_SOURCES.md for a list of sources that can be legally integrated
    // Example:
    // connectorRegistry.register(MyLegalConnector(executor))

    logger.info { "Registered ${connectorRegistry.getAllConnectors().count()} connectors" }
    if (connectorRegistry.getAllConnectors().none()) {
        logger.warn { "No connectors registered! Set CHAPTERVAULT_ENV=development to enable mock connectors, or add production connectors." }
    }

    // Initialize orchestrator with database integration
    val orchestrator = Orchestrator(
        connectorRegistry = connectorRegistry,
        storageSink = storageSink,
        seriesRepository = seriesRepository,
        chapterRepository = chapterRepository,
        downloadTaskRepository = downloadTaskRepository
    )
    logger.info { "Orchestrator initialized" }

    // Create API configuration
    val apiConfig = ApiConfiguration(
        orchestrator = orchestrator,
        connectorRegistry = connectorRegistry,
        seriesRepository = seriesRepository,
        chapterRepository = chapterRepository,
        downloadTaskRepository = downloadTaskRepository,
        storageDir = storageDir
    )

    // Server configuration
    val serverConfig = appConfig.server
    val port = serverConfig.port
    val host = serverConfig.host
    val baseUrl = serverConfig.baseUrl ?: "http://localhost:$port"

    // Create OPDS configuration
    val opdsConfig = OpdsConfiguration(
        seriesRepository = seriesRepository,
        chapterRepository = chapterRepository,
        storageBasePath = storageDir,
        baseUrl = "$baseUrl/opds"
    )

    val server = embeddedServer(Netty, port = port, host = host) {
        configureApi(apiConfig)
        configureOpds(opdsConfig)
    }

    logger.info { "Starting server on $host:$port" }
    logger.info { "${BuildConfig.APP_NAME} ready - API available at http://$host:$port" }

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "${BuildConfig.APP_NAME} shutting down..." }
        orchestrator.shutdown()
        server.stop(1000, 2000)
        logger.info { "${BuildConfig.APP_NAME} stopped" }
    })

    server.start(wait = true)
}
