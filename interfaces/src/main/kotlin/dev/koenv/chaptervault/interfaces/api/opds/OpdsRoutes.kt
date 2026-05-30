package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.interfaces.api.opds.FeedBuilder
import dev.koenv.chaptervault.interfaces.api.opds.OpdsV1
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.Instant

private val feedBuilder = FeedBuilder()
private val opdsV1 = OpdsV1()

private fun opdsContentType(call: io.ktor.server.application.ApplicationCall): ContentType {
    val accept = call.request.header(HttpHeaders.Accept) ?: ""
    return if (accept.contains("text/html")) {
        ContentType.parse("text/xml;charset=utf-8")
    } else {
        ContentType.parse("application/atom+xml;charset=utf-8")
    }
}

fun Application.opdsRoutes(
    libraryRead: LibraryReadApi,
    pageSource: ChapterPageSource,
) {
    routing {
        authenticate("auth-basic") {
            get("/opds/v1") {
                val feed = feedBuilder.buildNavigationFeed(Instant.now().toString())
                call.respondText(opdsV1.serialize(feed), opdsContentType(call))
            }

            get("/opds/v1/catalog") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                when (val result = libraryRead.listSeries(PageRequest(page, size.coerceIn(1, 100)))) {
                    is Result.Success -> {
                        val p = result.value
                        val feed =
                            feedBuilder.buildCatalogFeed(
                                series = p.items,
                                page = page,
                                size = p.size,
                                totalItems = p.totalItems,
                                now = Instant.now().toString(),
                            )
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respond(HttpStatusCode.InternalServerError)
                }
            }

            get("/opds/v1/series/{id}") {
                val id =
                    try {
                        Id.from(call.parameters["id"]!!)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                val seriesResult = libraryRead.getSeries(id)
                if (seriesResult is Result.Failure) {
                    val status =
                        if (seriesResult.error is AppError.NotFound) {
                            HttpStatusCode.NotFound
                        } else {
                            HttpStatusCode.InternalServerError
                        }
                    call.respond(status)
                    return@get
                }
                val chaptersResult = libraryRead.listChapters(id)
                if (chaptersResult is Result.Failure) {
                    call.respond(HttpStatusCode.InternalServerError)
                    return@get
                }
                val chapters = (chaptersResult as Result.Success).value
                val pageInfoByChapterId =
                    chapters
                        .filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
                        .mapNotNull { chapter ->
                            val pageCount =
                                chapter.pageCount ?: when (val r = pageSource.countPages(chapter)) {
                                    is Result.Success -> r.value
                                    is Result.Failure -> return@mapNotNull null
                                }
                            chapter.id.toString() to FeedBuilder.ChapterPageInfo(pageCount, "image/jpeg")
                        }.toMap()
                val now = Instant.now().toString()
                val feed =
                    feedBuilder.buildSeriesFeed(
                        series = (seriesResult as Result.Success).value,
                        chapters = chapters,
                        now = now,
                        pageInfoByChapterId = pageInfoByChapterId,
                    )
                call.respondText(opdsV1.serialize(feed), opdsContentType(call))
            }
        }
    }
}

fun Application.opdsPageRoutes(
    libraryRead: LibraryReadApi,
    pageSource: ChapterPageSource,
) {
    routing {
        authenticate("auth-basic") {
            get("/opds/v1/chapters/{id}/pages/{pageNumber}") {
                val id =
                    try {
                        Id.from(call.parameters["id"]!!)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                val pageNumber =
                    call.parameters["pageNumber"]?.toIntOrNull()
                        ?: run {
                            call.respond(HttpStatusCode.BadRequest)
                            return@get
                        }

                val chapterResult = libraryRead.getChapter(id)
                if (chapterResult is Result.Failure) {
                    val status =
                        if (chapterResult.error is AppError.NotFound) {
                            HttpStatusCode.NotFound
                        } else {
                            HttpStatusCode.InternalServerError
                        }
                    call.respond(status)
                    return@get
                }
                val chapter = (chapterResult as Result.Success).value
                if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                when (val pageResult = pageSource.readPage(chapter, pageNumber)) {
                    is Result.Failure -> call.respond(HttpStatusCode.NotFound)
                    is Result.Success -> {
                        val page = pageResult.value
                        val etag = "\"${sha256("$id:${chapter.updatedAt.epochSecond}:$pageNumber")}\""
                        call.response.headers.append(HttpHeaders.ETag, etag)
                        call.response.headers.append(HttpHeaders.CacheControl, "max-age=31536000, immutable")
                        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
                        if (ifNoneMatch == etag) {
                            call.respond(HttpStatusCode.NotModified)
                            return@get
                        }
                        call.respondBytes(page.data, ContentType.parse(page.mimeType))
                    }
                }
            }
        }
    }
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
