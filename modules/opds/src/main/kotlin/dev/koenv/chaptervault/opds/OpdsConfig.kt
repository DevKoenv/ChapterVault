package dev.koenv.chaptervault.opds

import dev.koenv.chaptervault.opds.catalog.OpdsCatalogGenerator
import dev.koenv.chaptervault.opds.routes.opdsRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import java.io.File

/**
 * Configure OPDS v1.2 catalog support
 */
fun Application.configureOpds(storageDir: File, baseUrl: String = "http://localhost:8080/opds") {
    val catalogGenerator = OpdsCatalogGenerator(storageDir, baseUrl)
    
    routing {
        opdsRoutes(catalogGenerator)
    }
}
