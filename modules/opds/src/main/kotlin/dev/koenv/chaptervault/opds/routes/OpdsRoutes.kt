package dev.koenv.chaptervault.opds.routes

import dev.koenv.chaptervault.opds.catalog.OpdsCatalogService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * OPDS routes for serving the catalog to reader applications
 *
 * Endpoints:
 * - GET /opds - Root catalog (navigation feed)
 * - GET /opds/series/{id} - Series feed (acquisition feed)
 * - GET /opds/download/{chapterId} - Download chapter file
 * - GET /opds/stream/{chapterId}/{page} - Stream individual page (PSE)
 * - GET /opds/search?q={query} - Search catalog
 */
fun Route.opdsRoutes(catalogService: OpdsCatalogService) {
    route("/opds") {

        // Root catalog - lists all series
        get {
            val feed = catalogService.generateRootFeed()
            call.respondText(
                text = feed,
                contentType = ContentType.parse(catalogService.contentType)
            )
        }

        // Series catalog - lists chapters in a series
        get("/series/{seriesId}") {
            val seriesIdStr = call.parameters["seriesId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing series ID")

            val seriesId = try {
                UUID.fromString(seriesIdStr)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid series ID format")
            }

            val feed = catalogService.generateSeriesFeed(seriesId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Series not found or has no downloaded chapters")

            call.respondText(
                text = feed,
                contentType = ContentType.parse(catalogService.contentType)
            )
        }

        // Download chapter file
        get("/download/{chapterId}") {
            val chapterIdStr = call.parameters["chapterId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing chapter ID")

            val chapterId = try {
                UUID.fromString(chapterIdStr)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid chapter ID format")
            }

            val file = catalogService.getChapterFile(chapterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Chapter file not found")

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    file.name
                ).toString()
            )
            call.respondFile(file)
        }

        // PSE: Stream individual page from chapter
        get("/stream/{chapterId}/{pageNumber}") {
            val chapterIdStr = call.parameters["chapterId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing chapter ID")
            val pageNumberStr = call.parameters["pageNumber"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing page number")

            val chapterId = try {
                UUID.fromString(chapterIdStr)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid chapter ID format")
            }

            val pageNumber = pageNumberStr.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid page number")

            val chapterInfo = catalogService.getChapterInfo(chapterId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Chapter not found")

            if (pageNumber < 0 || pageNumber >= chapterInfo.pageCount) {
                return@get call.respond(HttpStatusCode.BadRequest, "Page number out of range (0-${chapterInfo.pageCount - 1})")
            }

            // TODO: Implement actual page extraction from CBZ/folder
            // This would require a PageExtractor service
            call.respond(
                HttpStatusCode.NotImplemented,
                "Page streaming not yet implemented. Download the full chapter instead."
            )
        }

        // Search catalog
        get("/search") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing search query")

            if (query.length < 2) {
                return@get call.respond(HttpStatusCode.BadRequest, "Search query too short (min 2 characters)")
            }

            val feed = catalogService.searchSeries(query)
            call.respondText(
                text = feed,
                contentType = ContentType.parse(catalogService.contentType)
            )
        }
    }
}
