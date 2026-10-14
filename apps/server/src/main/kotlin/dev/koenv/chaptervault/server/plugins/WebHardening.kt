package dev.koenv.chaptervault.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/**
 * Cross-origin access for browsers. Only the listed [origins] may call the API from another site; with none listed
 * nothing is allowed. Apps, OPDS readers and a UI served from the same origin do not need CORS at all.
 */
internal fun Application.configureCors(origins: List<String>) {
    if (origins.isEmpty()) return
    install(CORS) {
        origins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://", "http"))) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }
}

/** Headers every response carries, errors included. */
internal val DefaultSecurityHeaders =
    createApplicationPlugin("DefaultSecurityHeaders") {
        onCallRespond { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append(HttpHeaders.Referrer, "no-referrer")
            call.response.headers.append("X-Frame-Options", "DENY")
        }
    }
