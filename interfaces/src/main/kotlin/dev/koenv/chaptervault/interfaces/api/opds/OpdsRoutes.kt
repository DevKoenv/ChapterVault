package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.extensions.opds.FeedBuilder
import dev.koenv.chaptervault.extensions.opds.OpdsV1
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant

private val OPDS_CONTENT_TYPE = ContentType.parse("application/atom+xml;charset=utf-8")
private val feedBuilder = FeedBuilder()
private val opdsV1 = OpdsV1()

fun Application.opdsRoutes(libraryRead: LibraryReadApi, pageSource: ChapterPageSource) {
    routing {
        authenticate("auth-basic") {
            get("/opds/v1") {
                val feed = feedBuilder.buildNavigationFeed(Instant.now().toString())
                call.respondText(opdsV1.serialize(feed), OPDS_CONTENT_TYPE)
            }

            get("/opds/v1/catalog") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                when (val result = libraryRead.listSeries(PageRequest(page, size.coerceIn(1, 100)))) {
                    is Result.Success -> {
                        val p = result.value
                        val feed = feedBuilder.buildCatalogFeed(
                            series = p.items,
                            page = page,
                            size = p.size,
                            totalItems = p.totalItems,
                            now = Instant.now().toString(),
                        )
                        call.respondText(opdsV1.serialize(feed), OPDS_CONTENT_TYPE)
                    }
                    is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                }
            }

            get("/opds/v1/series/{id}") {
                val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                val seriesResult = libraryRead.getSeries(id)
                if (seriesResult is Result.Failure) {
                    val status = if (seriesResult.error is AppError.NotFound) HttpStatusCode.NotFound
                    else HttpStatusCode.InternalServerError
                    call.respond(status); return@get
                }
                val chaptersResult = libraryRead.listChapters(id)
                if (chaptersResult is Result.Failure) {
                    call.respond(HttpStatusCode.InternalServerError); return@get
                }
                val downloadedChapters = (chaptersResult as Result.Success).value
                val pageInfoByChapterId = downloadedChapters
                    .filter { it.downloadStatus == DownloadStatus.DOWNLOADED && it.pageCount != null }
                    .mapNotNull { chapter ->
                        val mimeType = when (val r = pageSource.readPage(chapter, 0)) {
                            is Result.Success -> r.value.mimeType
                            is Result.Failure -> "image/*"
                        }
                        chapter.id.toString() to FeedBuilder.ChapterPageInfo(chapter.pageCount!!, mimeType)
                    }
                    .toMap()
                val now = Instant.now().toString()
                val feed = feedBuilder.buildSeriesFeed(
                    series = (seriesResult as Result.Success).value,
                    chapters = downloadedChapters,
                    now = now,
                    pageInfoByChapterId = pageInfoByChapterId,
                )
                call.respondText(opdsV1.serialize(feed), OPDS_CONTENT_TYPE)
            }
        }
    }
}
