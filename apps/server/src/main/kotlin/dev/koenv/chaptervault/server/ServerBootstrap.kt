package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.NotificationService
import dev.koenv.chaptervault.infrastructure.SeriesRefreshScheduler
import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.infrastructure.update.UpdateCheckerService
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.interfaces.serialization.dto.v1.ErrorResponse
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationDispatchApi
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.server.auth.configureAuthentication
import dev.koenv.chaptervault.server.background.launchBackgroundJobs
import dev.koenv.chaptervault.server.generated.BuildConfig
import dev.koenv.chaptervault.server.plugins.AuthRateLimiting
import dev.koenv.chaptervault.server.plugins.RequestBodyLimit
import dev.koenv.chaptervault.server.routing.configureHealthRoutes
import dev.koenv.chaptervault.server.routing.configureRouting
import dev.koenv.chaptervault.server.routing.configureSwagger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.sse.SSE
import org.koin.ktor.ext.inject
import org.slf4j.event.Level

private val logger = KotlinLogging.logger("ServerBootstrap")

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
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }
    install(SSE)
    val appRateLimitConfig = config.auth.rateLimiting
    install(AuthRateLimiting) {
        this.config = appRateLimitConfig
    }
    install(RequestBodyLimit)

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
    val backupApi by inject<BackupApi>()
    val readingStatusApi by inject<ReadingStatusApi>()
    val notificationService by inject<NotificationService>()
    val notificationApi by inject<NotificationApi>()
    val notificationDispatch by inject<NotificationDispatchApi>()
    val extensionLoaderService by inject<ExtensionLoaderService>()
    val updateCheckerService by inject<UpdateCheckerService>()
    val opdsRead by inject<OpdsReadApi>()

    configureAuthentication(auth)

    configureRouting(
        config = config,
        auth = auth,
        libraryRead = libraryRead,
        libraryCommand = libraryCommand,
        taskQueue = taskQueue,
        system = system,
        connectorRegistry = connectorRegistry,
        projectionService = projectionService,
        progressApi = progressApi,
        backupApi = backupApi,
        readingStatusApi = readingStatusApi,
        notificationApi = notificationApi,
        notificationDispatch = notificationDispatch,
        extensionLoaderService = extensionLoaderService,
        updateCheckerService = updateCheckerService,
        opdsRead = opdsRead,
        fileStorage = fileStorage,
    )

    notificationService.start()

    launchBackgroundJobs(config, auth, projectionService, executor, refreshScheduler)
    logger.info {
        "ChapterVault v${BuildConfig.VERSION} (rev ${BuildConfig.REVISION}) " +
            "listening on http://${config.server.host}:${config.server.port}"
    }
    updateCheckerService.startAsync()

    configureSwagger()

    // /health last, after all services are started
    configureHealthRoutes(executor)
}
