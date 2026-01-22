package dev.koenv.chaptervault.api

import dev.koenv.chaptervault.api.models.response.ErrorResponse
import dev.koenv.chaptervault.api.routes.downloadRoutes
import dev.koenv.chaptervault.api.routes.seriesRoutes
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * Configure the Ktor API server
 */
fun Application.configureApi(orchestrator: Orchestrator) {
    
    // Install plugins
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", cause.message ?: "Unknown error")
            )
        }
    }
    
    // Configure routing
    routing {
        get("/") {
            call.respondText("ChapterVault API is running", ContentType.Text.Plain)
        }
        
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
        }
        
        seriesRoutes(orchestrator)
        downloadRoutes(orchestrator)
    }
}
