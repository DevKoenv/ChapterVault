package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.interfaces.api.opds.opdsRoutes
import dev.koenv.chaptervault.interfaces.api.rest.adminRoutes
import dev.koenv.chaptervault.interfaces.api.rest.authRoutes
import dev.koenv.chaptervault.interfaces.api.rest.libraryRoutes
import dev.koenv.chaptervault.interfaces.api.rest.taskRoutes
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.interfaces.api.websocket.eventSocket
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.koin.ktor.ext.inject

fun Application.bootstrap() {
    // Install plugins
    install(ContentNegotiation) { json() }
    install(WebSockets)

    // Resolve dependencies from Koin
    // NOTE: system, auth, registry have no implementation bound yet (skeleton phase).
    // Server will throw NoBeanDefFoundException at startup until kernelModule bindings are added.
    val config by inject<AppConfig>()
    val libraryRead by inject<LibraryReadApi>()
    val libraryCommand by inject<LibraryCommandApi>()
    val system by inject<SystemApi>()
    val auth by inject<AuthApi>()
    val registry by inject<ExtensionRegistry>()
    val projectionService by inject<EventProjectionService>()

    // Mount routes: core REST → WebSocket → extension-contributed → health (always last)
    libraryRoutes(libraryRead, libraryCommand)
    taskRoutes(system)
    authRoutes(auth)
    eventSocket(projectionService)
    adminRoutes(registry)
    opdsRoutes(registry)

    // Health endpoint — always last, always responds
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
