package dev.koenv.chaptervault.server.routing

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.extensions.loader.ExtensionLoaderService
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.interfaces.api.opds.opdsPageRoutes
import dev.koenv.chaptervault.interfaces.api.opds.opdsRoutes
import dev.koenv.chaptervault.interfaces.api.rest.authRoutes
import dev.koenv.chaptervault.interfaces.api.rest.backupRoutes
import dev.koenv.chaptervault.interfaces.api.rest.connectorRoutes
import dev.koenv.chaptervault.interfaces.api.rest.extensionRoutes
import dev.koenv.chaptervault.interfaces.api.rest.idParam
import dev.koenv.chaptervault.interfaces.api.rest.libraryRoutes
import dev.koenv.chaptervault.interfaces.api.rest.notificationRoutes
import dev.koenv.chaptervault.interfaces.api.rest.progressRoutes
import dev.koenv.chaptervault.interfaces.api.rest.readingStatusRoutes
import dev.koenv.chaptervault.interfaces.api.rest.requirePermissionForAll
import dev.koenv.chaptervault.interfaces.api.rest.respondError
import dev.koenv.chaptervault.interfaces.api.rest.systemRoutes
import dev.koenv.chaptervault.interfaces.api.rest.taskRoutes
import dev.koenv.chaptervault.interfaces.api.rest.userRoutes
import dev.koenv.chaptervault.interfaces.api.sse.sseRoutes
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.kernel.api.ApiPaths
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
import dev.koenv.chaptervault.kernel.api.UpdateInfoApi
import dev.koenv.chaptervault.kernel.auth.Permission
import dev.koenv.chaptervault.kernel.connector.ConnectorRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

// takes every API the routes need; grouping them is tracked in the fix list (section G)
@Suppress("LongParameterList")
internal fun Application.configureRouting(
    config: AppConfig,
    auth: AuthApi,
    libraryRead: LibraryReadApi,
    libraryCommand: LibraryCommandApi,
    taskQueue: TaskQueue,
    system: SystemApi,
    connectorRegistry: ConnectorRegistry,
    projectionService: EventProjectionService,
    progressApi: ProgressApi,
    backupApi: BackupApi,
    readingStatusApi: ReadingStatusApi,
    notificationApi: NotificationApi,
    notificationDispatch: NotificationDispatchApi,
    extensionLoaderService: ExtensionLoaderService,
    updates: UpdateInfoApi,
    opdsRead: OpdsReadApi,
    fileStorage: FileStorage,
) {
    routing {
        route(ApiPaths.V1) {
            authRoutes(auth, config.auth.registrationEnabled)

            // cover images are served without auth, they're thumbnails not content
            get("/library/series/{id}/cover") {
                val id = call.idParam("id", "series") ?: return@get
                when (val result = fileStorage.readCover(id.toString())) {
                    is Result.Failure -> call.respondError(result.error)
                    is Result.Success -> {
                        val (bytes, mimeType) = result.value
                        call.respondBytes(bytes, ContentType.parse(mimeType))
                    }
                }
            }

            authenticate("auth-bearer") {
                userRoutes(auth)
                libraryRoutes(libraryRead, libraryCommand, taskQueue, fileStorage, connectorRegistry, readingStatusApi)
                taskRoutes(system)
                connectorRoutes(connectorRegistry, libraryRead)
                progressRoutes(progressApi)
                backupRoutes(backupApi)
                readingStatusRoutes(readingStatusApi)
                notificationRoutes(notificationApi, notificationDispatch)
                extensionRoutes(extensionLoaderService)
                sseRoutes(projectionService)
                systemRoutes(updates, fileStorage)
            }
        }
    }

    opdsRoutes(libraryRead, opdsRead, fileStorage, config.server.publicUrl)
    opdsPageRoutes(libraryRead, fileStorage)

    // here rather than :interfaces because it needs FileStorage directly
    routing {
        authenticate("auth-basic") {
            requirePermissionForAll(Permission.READ_LIBRARY)
            get("/opds/v1/download/{chapterId}") {
                val chapterId = call.idParam("chapterId", "chapter") ?: return@get
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
}

// kept separate from configureRouting: bootstrap() registers swagger after the background jobs are launched
internal fun Application.configureSwagger() {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }
}
