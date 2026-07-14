package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.NotificationService
import dev.koenv.chaptervault.infrastructure.SeriesRefreshScheduler
import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.infrastructure.update.UpdateCheckerService
import dev.koenv.chaptervault.interfaces.api.opds.opdsPageRoutes
import dev.koenv.chaptervault.interfaces.api.opds.opdsRoutes
import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.interfaces.api.rest.authRoutes
import dev.koenv.chaptervault.interfaces.api.rest.backupRoutes
import dev.koenv.chaptervault.interfaces.api.rest.connectorRoutes
import dev.koenv.chaptervault.interfaces.api.rest.extensionReloadRoutes
import dev.koenv.chaptervault.interfaces.api.rest.extensionRoutes
import dev.koenv.chaptervault.interfaces.api.rest.libraryRoutes
import dev.koenv.chaptervault.interfaces.api.rest.metadataOverrideRoutes
import dev.koenv.chaptervault.interfaces.api.rest.notificationRoutes
import dev.koenv.chaptervault.interfaces.api.rest.progressRoutes
import dev.koenv.chaptervault.interfaces.api.rest.readingStatusRoutes
import dev.koenv.chaptervault.interfaces.api.rest.respondBadRequest
import dev.koenv.chaptervault.interfaces.api.rest.respondError
import dev.koenv.chaptervault.interfaces.api.rest.systemRoutes
import dev.koenv.chaptervault.interfaces.api.rest.taskRoutes
import dev.koenv.chaptervault.interfaces.api.sse.sseRoutes
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.MetadataOverrideApi
import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.server.generated.BuildConfig
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject
import org.slf4j.event.Level
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger("ServerBootstrap")

private val basicAuthCache = ConcurrentHashMap<String, Pair<KtorPrincipal, Long>>()
private const val BASIC_AUTH_CACHE_TTL_MS = 60_000L

private fun basicAuthCacheKey(
    name: String,
    password: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$name:$password".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun Application.bootstrap() {
    val config by inject<dev.koenv.chaptervault.infrastructure.config.AppConfig>()
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
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception on ${call.request.httpMethod.value} ${call.request.uri}" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("NOT_FOUND", "The requested resource was not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorResponse("METHOD_NOT_ALLOWED", "HTTP method not allowed for this endpoint"))
        }
    }
    install(CORS) {
        if (config.server.corsOrigins.isEmpty()) {
            anyHost()
        } else {
            config.server.corsOrigins.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }
    install(SSE)
    val appRateLimitConfig = config.auth.rateLimiting
    install(AuthRateLimiting) {
        this.config = appRateLimitConfig
    }

    val libraryRead by inject<LibraryReadApi>()
    val libraryCommand by inject<LibraryCommandApi>()
    val taskQueue by inject<TaskQueue>()
    val system by inject<SystemApi>()
    val auth by inject<AuthApi>()
    val connectorRegistry by inject<ConnectorRegistry>()
    val projectionService by inject<EventProjectionService>()
    val executor by inject<TaskExecutorService>()
    val refreshScheduler by inject<SeriesRefreshScheduler>()
    val progressApi by inject<ProgressApi>()
    val metadataOverrideApi by inject<MetadataOverrideApi>()
    val backupApi by inject<BackupApi>()
    val readingStatusApi by inject<ReadingStatusApi>()
    val notificationService by inject<NotificationService>()
    val notificationApi by inject<NotificationApi>()
    val notificationDispatch by inject<NotificationDispatchApi>()
    val extensionLoaderService by inject<ExtensionLoaderService>()
    val updateCheckerService by inject<UpdateCheckerService>()

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
                val key = basicAuthCacheKey(credential.name, credential.password)
                val cached = basicAuthCache[key]
                if (cached != null && cached.second > System.currentTimeMillis()) {
                    return@validate cached.first
                }
                when (val result = auth.validateCredentials(Credentials(credential.name, credential.password))) {
                    is Result.Success ->
                        KtorPrincipal(result.value).also {
                            basicAuthCache[key] = it to (System.currentTimeMillis() + BASIC_AUTH_CACHE_TTL_MS)
                        }
                    is Result.Failure -> null
                }
            }
        }
    }

    authRoutes(auth)

    routing {
        authenticate("auth-bearer") {
            libraryRoutes(libraryRead, libraryCommand, taskQueue, fileStorage, connectorRegistry, readingStatusApi)
            taskRoutes(system)
            connectorRoutes(connectorRegistry, libraryRead)
            progressRoutes(progressApi)
            metadataOverrideRoutes(metadataOverrideApi)
            backupRoutes(backupApi)
            readingStatusRoutes(readingStatusApi)
            notificationRoutes(notificationApi, notificationDispatch)
            extensionRoutes(extensionLoaderService)
            extensionReloadRoutes(extensionLoaderService)
            sseRoutes(projectionService)
        }
    }

    val opdsRead by inject<OpdsReadApi>()
    opdsRoutes(libraryRead, opdsRead, fileStorage)
    opdsPageRoutes(libraryRead, fileStorage)

    // here rather than :interfaces because it needs FileStorage directly
    routing {
        authenticate("auth-basic") {
            get("/opds/v1/download/{chapterId}") {
                val chapterId =
                    try {
                        Id.from(call.parameters["chapterId"]!!)
                    } catch (e: Exception) {
                        call.respondBadRequest("Invalid chapter ID")
                        return@get
                    }
                when (val chapterResult = libraryRead.getChapter(chapterId)) {
                    is Result.Failure -> {
                        call.respondError(chapterResult.error)
                        return@get
                    }
                    is Result.Success -> {
                        val chapter = chapterResult.value
                        if (!fileStorage.chapterExists(chapter)) {
                            call.respondError(AppError.NotFound("Chapter file", chapterId.toString()))
                            return@get
                        }
                        call.respondOutputStream(ContentType.parse("application/x-cbz")) {
                            fileStorage.streamChapterTo(chapter, this)
                        }
                    }
                }
            }
        }
    }

    // cover images are served without auth, they're thumbnails not content
    routing {
        get("/library/series/{id}/cover") {
            val id =
                try {
                    Id.from(call.parameters["id"]!!)
                } catch (e: Exception) {
                    call.respondBadRequest("Invalid series ID")
                    return@get
                }
            when (val result = fileStorage.readCover(id.toString())) {
                is Result.Failure -> call.respondError(result.error)
                is Result.Success -> {
                    val (bytes, mimeType) = result.value
                    call.respondBytes(bytes, ContentType.parse(mimeType))
                }
            }
        }
    }

    routing {
        authenticate("auth-bearer") {
            systemRoutes(updateCheckerService)
        }
    }

    notificationService.start()

    launch { projectionService.start() }
    launch {
        executor.recoverOnBoot()
        executor.start()
    }
    launch { refreshScheduler.start() }
    logger.info { "ChapterVault v${BuildConfig.VERSION} (rev ${BuildConfig.REVISION}) listening on http://${config.server.host}:${config.server.port}" }
    updateCheckerService.startAsync()

    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }

    // /health last, after all services are started
    routing {
        get("/health") {
            val dbOk = DatabaseFactory.ping()
            val executorOk = executor.isAlive()
            val status = if (dbOk && executorOk) "ok" else "degraded"
            val httpStatus = if (status == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                httpStatus,
                buildJsonObject {
                    put("status", status)
                    putJsonObject("checks") {
                        put("database", if (dbOk) "ok" else "error")
                        put("executor", if (executorOk) "ok" else "error")
                    }
                },
            )
        }
    }
}
