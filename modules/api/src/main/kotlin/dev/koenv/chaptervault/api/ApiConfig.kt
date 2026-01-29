package dev.koenv.chaptervault.api

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.routes.catalogRoutes
import dev.koenv.chaptervault.api.routes.downloadRoutes
import dev.koenv.chaptervault.api.routes.libraryRoutes
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.database.repository.ChapterRepository
import dev.koenv.chaptervault.database.repository.DownloadTaskRepository
import dev.koenv.chaptervault.database.repository.SeriesRepository
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import kotlinx.serialization.json.Json
import java.io.File

data class ApiConfiguration(
    val orchestrator: Orchestrator,
    val connectorRegistry: ConnectorRegistry,
    val seriesRepository: SeriesRepository,
    val chapterRepository: ChapterRepository,
    val downloadTaskRepository: DownloadTaskRepository,
    val storageDir: File
)

fun Application.configureApi(config: ApiConfiguration) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ProblemDetail(
                    type = ErrorTypes.VALIDATION,
                    title = "Bad Request",
                    status = 400,
                    detail = cause.message ?: "Invalid request",
                    instance = call.request.local.uri
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ProblemDetail(
                    type = ErrorTypes.INTERNAL_ERROR,
                    title = "Internal Server Error",
                    status = 500,
                    detail = cause.message ?: "Unknown error",
                    instance = call.request.local.uri
                )
            )
        }
    }

    routing {
        get("/") {
            call.respondText("ChapterVault API v1 is running", ContentType.Text.Plain)
        }

        get("/health") {
            val diskSpace = config.storageDir.usableSpace / (1024 * 1024)
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "healthy",
                    "version" to "1.0.0",
                    "storage" to mapOf(
                        "path" to config.storageDir.absolutePath,
                        "freeSpaceMB" to diskSpace
                    )
                )
            )
        }

        swaggerUI("/swagger") {
            info = OpenApiInfo("ChapterVault API", "1.0.0")
            source = OpenApiDocSource.Routing(ContentType.Application.Json) {
                routingRoot.descendants()
            }
        }

        catalogRoutes(config.orchestrator, config.connectorRegistry, config.seriesRepository, config.chapterRepository)
        downloadRoutes(config.orchestrator, config.seriesRepository, config.chapterRepository, config.downloadTaskRepository)
        libraryRoutes(config.seriesRepository, config.chapterRepository)
    }
}
