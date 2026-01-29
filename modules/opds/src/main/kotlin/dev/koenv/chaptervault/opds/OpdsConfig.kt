package dev.koenv.chaptervault.opds

import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.opds.catalog.OpdsCatalogService
import dev.koenv.chaptervault.opds.model.OpdsVersion
import dev.koenv.chaptervault.opds.routes.opdsRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import java.io.File

/**
 * OPDS module configuration
 */
data class OpdsConfiguration(
    val seriesRepository: SeriesRepository,
    val chapterRepository: ChapterRepository,
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
    seriesRepository: SeriesRepository,
    chapterRepository: ChapterRepository,
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
