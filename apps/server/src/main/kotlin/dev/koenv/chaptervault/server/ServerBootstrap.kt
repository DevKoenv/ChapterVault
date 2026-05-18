package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.extensions.connectors.ConnectorRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.interfaces.api.opds.opdsRoutes
import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.interfaces.api.rest.adminRoutes
import dev.koenv.chaptervault.interfaces.api.rest.authRoutes
import dev.koenv.chaptervault.interfaces.api.rest.connectorRoutes
import dev.koenv.chaptervault.interfaces.api.rest.libraryRoutes
import dev.koenv.chaptervault.interfaces.api.rest.taskRoutes
import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import dev.koenv.chaptervault.interfaces.api.websocket.eventSocket
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.coroutines.launch
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import org.koin.ktor.ext.inject

fun Application.bootstrap() {
    install(ContentNegotiation) { json() }
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

    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { credential ->
                when (val result = auth.validateSession(credential.token)) {
                    is Result.Success -> KtorPrincipal(result.value)
                    is Result.Failure -> null
                }
            }
        }
    }

    // Public routes (no auth required)
    authRoutes(auth)

    // Protected routes
    routing {
        authenticate("auth-bearer") {
            libraryRoutes(libraryRead, libraryCommand, taskQueue)
            taskRoutes(system)
            adminRoutes(registry)
            connectorRoutes(connectorRegistry)
            eventSocket(projectionService)
        }
    }

    opdsRoutes(registry)

    launch { executor.recoverOnBoot(); executor.start() }

    // /health must be last
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
