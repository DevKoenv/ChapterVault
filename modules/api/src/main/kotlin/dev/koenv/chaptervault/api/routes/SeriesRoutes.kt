package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.request.SearchRequest
import dev.koenv.chaptervault.api.models.response.*
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.seriesRoutes(orchestrator: Orchestrator) {
    
    route("/series") {
        
        // Search for series
        post("/search") {
            val request = call.receive<SearchRequest>()
            val results = orchestrator.searchSeries(request.query)
            
            val response = results.map { result ->
                SeriesSearchResponse(
                    url = result.url,
                    title = result.title,
                    description = result.description,
                    coverUrl = result.coverUrl
                )
            }
            
            call.respond(HttpStatusCode.OK, response)
        }
        
        // Get series metadata
        get("/metadata") {
            val url = call.request.queryParameters["url"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "URL parameter is required")
                )
            
            val metadata = orchestrator.fetchSeriesMetadata(url)
            
            val response = SeriesMetadataResponse(
                url = metadata.url,
                title = metadata.title,
                description = metadata.description,
                author = metadata.author,
                coverUrl = metadata.coverUrl,
                tags = metadata.tags,
                status = metadata.status.name
            )
            
            call.respond(HttpStatusCode.OK, response)
        }
        
        // Get chapter list
        get("/chapters") {
            val url = call.request.queryParameters["url"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "URL parameter is required")
                )
            
            val chapters = orchestrator.fetchChapterList(url)
            
            val response = chapters.map { chapter ->
                ChapterMetadataResponse(
                    url = chapter.url,
                    seriesUrl = chapter.seriesUrl,
                    title = chapter.title,
                    chapterNumber = chapter.chapterNumber,
                    publishDate = chapter.publishDate,
                    pageCount = chapter.pageCount
                )
            }
            
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
