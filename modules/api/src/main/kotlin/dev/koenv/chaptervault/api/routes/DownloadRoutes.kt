package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.request.DownloadRequest
import dev.koenv.chaptervault.api.models.response.*
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.downloadRoutes(orchestrator: Orchestrator) {
    
    route("/download") {
        
        // Download a chapter
        post("/chapter") {
            val request = call.receive<DownloadRequest>()
            val taskId = orchestrator.downloadChapter(request.url)
            
            call.respond(
                HttpStatusCode.Accepted,
                DownloadResponse(taskId, "Chapter download started")
            )
        }
        
        // Download entire series
        post("/series") {
            val request = call.receive<DownloadRequest>()
            val taskId = orchestrator.downloadSeries(request.url)
            
            call.respond(
                HttpStatusCode.Accepted,
                DownloadResponse(taskId, "Series download started")
            )
        }
        
        // Get task progress
        get("/progress/{taskId}") {
            val taskId = call.parameters["taskId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "Task ID is required")
                )
            
            val progress = orchestrator.getProgress(taskId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("not_found", "Task not found")
                )
            
            val response = TaskProgressResponse(
                taskId = progress.taskId,
                status = progress.status.name,
                message = progress.message,
                current = progress.current,
                total = progress.total,
                error = progress.error
            )
            
            call.respond(HttpStatusCode.OK, response)
        }
        
        // Get all task progress
        get("/progress") {
            val allProgress = orchestrator.getAllProgress()
            
            val response = allProgress.map { progress ->
                TaskProgressResponse(
                    taskId = progress.taskId,
                    status = progress.status.name,
                    message = progress.message,
                    current = progress.current,
                    total = progress.total,
                    error = progress.error
                )
            }
            
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
