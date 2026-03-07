package dev.koenv.chaptervault.api

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.api.routes.adminRoutes
import dev.koenv.chaptervault.api.routes.catalogRoutes
import dev.koenv.chaptervault.api.routes.libraryRoutes
import dev.koenv.chaptervault.api.routes.taskRoutes
import dev.koenv.chaptervault.orchestration.cache.CacheCleanupService
import dev.koenv.chaptervault.orchestration.ratelimit.SiteRateLimiter
import dev.koenv.chaptervault.core.BuildConfig
import dev.koenv.chaptervault.core.connector.ConnectorRegistry
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.DownloadTaskRepositoryPort
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
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
import kotlinx.serialization.json.*
import java.io.File

data class ApiConfiguration(
    val orchestrator: Orchestrator,
    val connectorRegistry: ConnectorRegistry,
    val seriesRepository: SeriesRepositoryPort,
    val chapterRepository: ChapterRepositoryPort,
    val downloadTaskRepository: DownloadTaskRepositoryPort,
    val storageDir: File,
    val cacheCleanupService: CacheCleanupService? = null,
    val siteRateLimiter: SiteRateLimiter? = null
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
            call.respondText(
                "${BuildConfig.APP_NAME} API is running version ${BuildConfig.VERSION}",
                ContentType.Text.Plain
            )
        }

        get("/health") {
            val usableBytes = config.storageDir.usableSpace

            val freeMiB = usableBytes / (1024 * 1024) // binary MB
            val freeMB = usableBytes / 1_000_000 // decimal MB

            val response = buildJsonObject {
                put("status", "healthy")
                put("version", BuildConfig.VERSION)
                put("environment", if (BuildConfig.isDevelopment) "development" else "production")

                putJsonObject("storage") {
                    put("path", config.storageDir.absolutePath)
                    put("freeMiB", freeMiB)
                    put("freeMB", freeMB)
                }
            }

            call.respond(HttpStatusCode.OK, response)
        }

        swaggerUI("/swagger") {
            info = OpenApiInfo("${BuildConfig.APP_NAME} API", BuildConfig.VERSION)
            source = OpenApiDocSource.Routing(ContentType.Application.Json) {
                routingRoot.descendants()
            }
        }

        catalogRoutes(config.orchestrator, config.connectorRegistry, config.seriesRepository, config.chapterRepository)
        taskRoutes(config.downloadTaskRepository)
        libraryRoutes(config.seriesRepository, config.chapterRepository, config.orchestrator, config.downloadTaskRepository)
        adminRoutes(config.cacheCleanupService, config.siteRateLimiter, config.orchestrator)
    }
}
