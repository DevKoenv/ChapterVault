package dev.koenv.chaptervault.app

import dev.koenv.chaptervault.api.ApiConfiguration
import dev.koenv.chaptervault.api.configureApi
import dev.koenv.chaptervault.opds.OpdsConfiguration
import dev.koenv.chaptervault.opds.configureOpds
import dev.koenv.chaptervault.connectors.impl.MockConnector
import dev.koenv.chaptervault.connectors.impl.SampleConnector
import dev.koenv.chaptervault.connectors.registry.ConnectorRegistryImpl
import dev.koenv.chaptervault.database.DatabaseConfig
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.DownloadTaskRepository
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
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
    logger.info { "ChapterVault starting..." }

    // Initialize database
    val dataDir = File(
        System.getenv("CHAPTERVAULT_DATA_PATH")
            ?: "${System.getProperty("user.home")}/ChapterVault/data"
    )
    dataDir.mkdirs()
    logger.info { "Database directory: ${dataDir.absolutePath}" }

    val database = DatabaseConfig.initialize(dataDir)
    logger.info { "Database initialized" }

    // Initialize repositories
    val seriesRepository = SeriesRepository(database).also { it.initialize() }
    val chapterRepository = ChapterRepository(database).also { it.initialize() }
    val downloadTaskRepository = DownloadTaskRepository(database).also { it.initialize() }
    logger.info { "Repositories initialized" }

    // Initialize storage
    val storageDir = File(
        System.getenv("CHAPTERVAULT_STORAGE_PATH")
            ?: "${System.getProperty("user.home")}/ChapterVault/downloads"
    )
    storageDir.mkdirs()
    logger.info { "Storage directory configured: ${storageDir.absolutePath}" }
    val storageSink = FileStorageSink(storageDir)

    // Initialize connector registry
    val connectorRegistry = ConnectorRegistryImpl()

    // Register connectors
    connectorRegistry.register(MockConnector())
    connectorRegistry.register(SampleConnector())

    logger.info { "Registered ${connectorRegistry.getAllConnectors().count()} connectors" }

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

    // Start API server
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val baseUrl = System.getenv("CHAPTERVAULT_BASE_URL") ?: "http://localhost:$port"

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
    
    logger.info { "Starting server on port $port" }
    logger.info { "ChapterVault ready - API available at http://localhost:$port" }
    
    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "ChapterVault shutting down..." }
        orchestrator.shutdown()
        server.stop(1000, 2000)
        logger.info { "ChapterVault stopped" }
    })
    
    server.start(wait = true)
}
