package dev.koenv.chaptervault.interfaces.api.opds

import dev.koenv.chaptervault.interfaces.api.rest.idParam
import dev.koenv.chaptervault.interfaces.api.rest.pageRequest
import dev.koenv.chaptervault.interfaces.api.rest.requirePermissionForAll
import dev.koenv.chaptervault.interfaces.api.rest.requirePrincipal
import dev.koenv.chaptervault.interfaces.api.rest.respondBadRequest
import dev.koenv.chaptervault.interfaces.api.rest.respondError
import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.OpdsSeriesFilter
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
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
    opdsRead: OpdsReadApi,
    pageSource: ChapterPageSource,
) {
    routing {
        authenticate("auth-basic") {
            requirePermissionForAll(Permission.READ_LIBRARY)
            get("/opds/v1") {
                val feed = feedBuilder.buildNavigationFeed(Instant.now().toString())
                call.respondText(opdsV1.serialize(feed), opdsContentType(call))
            }

            get("/opds/v1/catalog") {
                val pageRequest = call.pageRequest(defaultSize = 20)
                val page = pageRequest.page
                val genre = call.request.queryParameters["genre"]
                val language = call.request.queryParameters["language"]
                val upstreamStatus = call.request.queryParameters["upstreamStatus"]
                val now = Instant.now().toString()

                if (genre != null || language != null || upstreamStatus != null) {
                    val filter = OpdsSeriesFilter(genre = genre, language = language, upstreamStatus = upstreamStatus)
                    when (val result = opdsRead.listSeriesFiltered(filter, pageRequest)) {
                        is Result.Success -> {
                            val p = result.value
                            val feed =
                                feedBuilder.buildFilteredCatalogFeed(
                                    series = p.items,
                                    page = page,
                                    size = p.size,
                                    totalItems = p.totalItems,
                                    now = now,
                                    genre = genre,
                                    language = language,
                                    upstreamStatus = upstreamStatus,
                                )
                            call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                        }
                        is Result.Failure -> call.respondError(result.error)
                    }
                } else {
                    when (val result = libraryRead.listSeries(pageRequest)) {
                        is Result.Success -> {
                            val p = result.value
                            val feed =
                                feedBuilder.buildCatalogFeed(
                                    series = p.items,
                                    page = page,
                                    size = p.size,
                                    totalItems = p.totalItems,
                                    now = now,
                                )
                            call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                        }
                        is Result.Failure -> call.respondError(result.error)
                    }
                }
            }

            get("/opds/v1/history") {
                val principal = call.requirePrincipal() ?: return@get
                val pageRequest = call.pageRequest(defaultSize = 20)
                when (val result = opdsRead.listReadHistory(principal.user.id, pageRequest)) {
                    is Result.Success -> {
                        val p = result.value
                        val feed =
                            feedBuilder.buildHistoryFeed(
                                entries = p.items,
                                page = pageRequest.page,
                                size = p.size,
                                totalItems = p.totalItems,
                                now = Instant.now().toString(),
                            )
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respondError(result.error)
                }
            }

            get("/opds/v1/library-updates") {
                val pageRequest = call.pageRequest(defaultSize = 20)
                when (val result = opdsRead.listRecentlyAddedChapters(pageRequest)) {
                    is Result.Success -> {
                        val p = result.value
                        val feed =
                            feedBuilder.buildLibraryUpdatesFeed(
                                chapters = p.items,
                                page = pageRequest.page,
                                size = p.size,
                                totalItems = p.totalItems,
                                now = Instant.now().toString(),
                            )
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respondError(result.error)
                }
            }

            get("/opds/v1/library/genres") {
                when (val result = opdsRead.listGenres()) {
                    is Result.Success -> {
                        val feed = feedBuilder.buildGenreNavFeed(result.value, Instant.now().toString())
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respondError(result.error)
                }
            }

            get("/opds/v1/library/languages") {
                when (val result = opdsRead.listLanguages()) {
                    is Result.Success -> {
                        val feed = feedBuilder.buildLanguageNavFeed(result.value, Instant.now().toString())
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respondError(result.error)
                }
            }

            get("/opds/v1/library/status") {
                when (val result = opdsRead.listUpstreamStatuses()) {
                    is Result.Success -> {
                        val feed = feedBuilder.buildStatusNavFeed(result.value, Instant.now().toString())
                        call.respondText(opdsV1.serialize(feed), opdsContentType(call))
                    }
                    is Result.Failure -> call.respondError(result.error)
                }
            }

            get("/opds/v1/series/{id}") {
                val id = call.idParam("id", "series") ?: return@get
                val seriesResult = libraryRead.getSeries(id)
                if (seriesResult is Result.Failure) {
                    call.respondError(seriesResult.error)
                    return@get
                }
                val chaptersResult = libraryRead.listChapters(id)
                if (chaptersResult is Result.Failure) {
                    call.respondError(chaptersResult.error)
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
            requirePermissionForAll(Permission.READ_LIBRARY)
            get("/opds/v1/chapters/{id}/pages/{pageNumber}") {
                val id = call.idParam("id", "chapter") ?: return@get
                val pageNumber =
                    call.parameters["pageNumber"]?.toIntOrNull()
                        ?: run {
                            call.respondBadRequest("Invalid page number")
                            return@get
                        }
                if (pageNumber < 0) {
                    call.respondError(AppError.NotFound("Page", pageNumber.toString()))
                    return@get
                }

                val chapterResult = libraryRead.getChapter(id)
                if (chapterResult is Result.Failure) {
                    call.respondError(chapterResult.error)
                    return@get
                }
                val chapter = (chapterResult as Result.Success).value
                if (chapter.downloadStatus != DownloadStatus.DOWNLOADED) {
                    call.respondError(AppError.NotFound("Chapter", id.toString()))
                    return@get
                }

                when (val pageResult = pageSource.readPage(chapter, pageNumber)) {
                    is Result.Failure -> call.respondError(pageResult.error)
                    is Result.Success -> {
                        val page = pageResult.value
                        val etag = "\"${sha256("$id:${chapter.updatedAt.epochSecond}:$pageNumber")}\""
                        call.response.headers.append(HttpHeaders.ETag, etag)
                        call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
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
