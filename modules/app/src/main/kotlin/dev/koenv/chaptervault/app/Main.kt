package dev.koenv.chaptervault.app

import dev.koenv.chaptervault.api.configureApi
import dev.koenv.chaptervault.opds.configureOpds
import dev.koenv.chaptervault.connectors.impl.MockConnector
import dev.koenv.chaptervault.connectors.impl.SampleConnector
import dev.koenv.chaptervault.connectors.registry.ConnectorRegistryImpl
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
    
    // Initialize storage
    val storageDir = File(System.getProperty("user.home"), "ChapterVault/downloads")
    storageDir.mkdirs()
    logger.info { "Storage directory configured: ${storageDir.absolutePath}" }
    val storageSink = FileStorageSink(storageDir)
    
    // Initialize connector registry
    val connectorRegistry = ConnectorRegistryImpl()
    
    // Register connector
    connectorRegistry.register(MockConnector())
    connectorRegistry.register(SampleConnector())

    logger.info { "Registered ${connectorRegistry.getAllConnectors().count()} connectors" }

    // Initialize orchestrator
    val orchestrator = Orchestrator(
        connectorRegistry = connectorRegistry,
        storageSink = storageSink
    )
    logger.info { "Orchestrator initialized" }
    
    // Start API server
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureApi(orchestrator)
        configureOpds(storageDir, "http://localhost:$port/opds")
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
