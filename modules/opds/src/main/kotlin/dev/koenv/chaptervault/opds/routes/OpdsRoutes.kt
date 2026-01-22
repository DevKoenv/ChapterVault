package dev.koenv.chaptervault.opds.routes

import dev.koenv.chaptervault.opds.catalog.OpdsCatalogGenerator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

/**
 * OPDS v1.2 routes for serving comic catalog
 */
fun Route.opdsRoutes(catalogGenerator: OpdsCatalogGenerator) {
    
    route("/opds") {
        
        // Root catalog
        get {
            val feed = catalogGenerator.generateRootFeed()
            call.respondText(feed, ContentType.Application.Xml)
        }
        
        // Series catalog
        get("/series/{seriesId}") {
            val seriesId = call.parameters["seriesId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val feed = catalogGenerator.generateSeriesFeed(seriesId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            
            call.respondText(feed, ContentType.Application.Xml)
        }
        
        // Download chapter
        get("/download/{seriesId}/{chapterId}") {
            val seriesId = call.parameters["seriesId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val chapterId = call.parameters["chapterId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            
            val file = catalogGenerator.getChapterFile(seriesId, chapterId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    file.name
                ).toString()
            )
            call.respondFile(file)
        }
    }
}
