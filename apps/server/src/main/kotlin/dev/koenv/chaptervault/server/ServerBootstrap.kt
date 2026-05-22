package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BookmarkApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.interfaces.api.opds.opdsRoutes
import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.interfaces.api.rest.adminRoutes
import dev.koenv.chaptervault.interfaces.api.rest.authRoutes
import dev.koenv.chaptervault.interfaces.api.rest.connectorRoutes
import dev.koenv.chaptervault.interfaces.api.rest.bookmarkRoutes
import dev.koenv.chaptervault.interfaces.api.rest.libraryRoutes
import dev.koenv.chaptervault.interfaces.api.rest.progressRoutes
import dev.koenv.chaptervault.interfaces.api.rest.taskRoutes
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.interfaces.api.websocket.eventSocket
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import org.slf4j.event.Level
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

fun Application.bootstrap() {
    val fileStorage by inject<FileStorage>()
    fileStorage.ensureDirectories()

    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            "$method $uri -> $status"
        }
    }
    install(ContentNegotiation) { json() }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }
    install(WebSockets)

    val libraryRead by inject<LibraryReadApi>()
    val libraryCommand by inject<LibraryCommandApi>()
    val taskQueue by inject<TaskQueue>()
    val system by inject<SystemApi>()
    val auth by inject<AuthApi>()
    val registry by inject<ExtensionRegistry>()
    val connectorRegistry by inject<ConnectorRegistry>()
    val projectionService by inject<EventProjectionService>()
    val executor by inject<TaskExecutorService>()
    val progressApi by inject<ProgressApi>()
    val bookmarkApi by inject<BookmarkApi>()

    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { credential ->
                when (val result = auth.validateSession(credential.token)) {
                    is Result.Success -> KtorPrincipal(result.value)
                    is Result.Failure -> null
                }
            }
        }
        basic("auth-basic") {
            realm = "ChapterVault"
            validate { credential ->
                when (val result = auth.authenticate(Credentials(credential.name, credential.password))) {
                    is Result.Success -> KtorPrincipal(result.value.first)
                    is Result.Failure -> null
                }
            }
        }
    }

    // Public routes (no auth required)
    authRoutes(auth)

    // Bearer-protected routes
    routing {
        authenticate("auth-bearer") {
            libraryRoutes(libraryRead, libraryCommand, taskQueue, fileStorage)
            taskRoutes(system)
            adminRoutes(registry)
            connectorRoutes(connectorRegistry, libraryRead)
            progressRoutes(progressApi)
            bookmarkRoutes(bookmarkApi)
            eventSocket(projectionService)
        }
    }

    // OPDS feeds (Basic Auth)
    opdsRoutes(libraryRead)

    // OPDS chapter download (Basic Auth — in server module to access FileStorage)
    routing {
        authenticate("auth-basic") {
            get("/opds/v1/download/{chapterId}") {
                val chapterId = try { Id.from(call.parameters["chapterId"]!!) } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest); return@get
                }
                when (val chapterResult = libraryRead.getChapter(chapterId)) {
                    is Result.Failure -> { call.respond(HttpStatusCode.NotFound); return@get }
                    is Result.Success -> {
                        val chapter = chapterResult.value
                        when (val bytesResult = fileStorage.readChapterBytes(chapter)) {
                            is Result.Failure -> { call.respond(HttpStatusCode.NotFound); return@get }
                            is Result.Success -> call.respondBytes(bytesResult.value, ContentType.parse("application/x-cbz"))
                        }
                    }
                }
            }
        }
    }

    // Cover images are served without auth — they're thumbnails, not content
    routing {
        get("/library/series/{id}/cover") {
            val id = try { Id.from(call.parameters["id"]!!) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest); return@get
            }
            when (val result = fileStorage.readCover(id.toString())) {
                is Result.Failure -> call.respond(HttpStatusCode.NotFound)
                is Result.Success -> {
                    val (bytes, mimeType) = result.value
                    call.respondBytes(bytes, ContentType.parse(mimeType))
                }
            }
        }
    }

    launch { executor.recoverOnBoot(); executor.start() }

    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }

    // /health must be last
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
