package dev.koenv.chaptervault.opds

import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.opds.catalog.OpdsCatalogService
import dev.koenv.chaptervault.opds.model.OpdsVersion
import dev.koenv.chaptervault.opds.routes.opdsRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import java.io.File

/**
 * OPDS module configuration.
 *
 * Configure via environment variables:
 * - CHAPTERVAULT_BASE_URL: Base URL for the server (e.g., "https://comics.example.com")
 *   The OPDS endpoint will be at {CHAPTERVAULT_BASE_URL}/opds
 *
 * Note: OPDS entry IDs use full URLs as per the Atom/OPDS specification.
 * This is intentional and ensures globally unique identifiers.
 */
data class OpdsConfiguration(
    val seriesRepository: SeriesRepositoryPort,
    val chapterRepository: ChapterRepositoryPort,
    val storageBasePath: File,
    val baseUrl: String = "http://localhost:8080/opds",
    val version: OpdsVersion = OpdsVersion.V1_2,
    val enablePse: Boolean = true
)

/**
 * Configure OPDS catalog support
 *
 * Example usage:
 * ```kotlin
 * install(Routing) {
 *     configureOpds(OpdsConfiguration(
 *         seriesRepository = seriesRepo,
 *         chapterRepository = chapterRepo,
 *         storageBasePath = File("./storage"),
 *         baseUrl = "http://localhost:8080/opds"
 *     ))
 * }
 * ```
 */
fun Application.configureOpds(config: OpdsConfiguration) {
    val catalogService = OpdsCatalogService(
        seriesRepository = config.seriesRepository,
        chapterRepository = config.chapterRepository,
        storageBasePath = config.storageBasePath,
        baseUrl = config.baseUrl,
        version = config.version,
        enablePse = config.enablePse
    )

    routing {
        opdsRoutes(catalogService)
    }
}

/**
 * Alternative: Configure OPDS with inline parameters (for simpler setup)
 */
fun Application.configureOpds(
    seriesRepository: SeriesRepositoryPort,
    chapterRepository: ChapterRepositoryPort,
    storageBasePath: File,
    baseUrl: String = "http://localhost:8080/opds"
) {
    configureOpds(OpdsConfiguration(
        seriesRepository = seriesRepository,
        chapterRepository = chapterRepository,
        storageBasePath = storageBasePath,
        baseUrl = baseUrl
    ))
}
