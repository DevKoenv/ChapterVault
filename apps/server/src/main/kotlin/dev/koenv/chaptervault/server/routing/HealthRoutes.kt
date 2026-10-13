package dev.koenv.chaptervault.server.routing

import dev.koenv.chaptervault.infrastructure.TaskExecutorService
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun Application.configureHealthRoutes(executor: TaskExecutorService) {
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
