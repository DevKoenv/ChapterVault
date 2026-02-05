package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.orchestration.cache.CacheCleanupService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Admin routes for system maintenance operations.
 */
fun Route.adminRoutes(
    cacheCleanupService: CacheCleanupService?
) {
    route("/api/v1/admin") {

        /**
         * POST /api/v1/admin/cache/cleanup
         * Manually trigger cache cleanup of non-library series.
         */
        post("/cache/cleanup") {
            if (cacheCleanupService == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Cache Cleanup Unavailable",
                        status = 503,
                        detail = "Cache cleanup service is not configured",
                        instance = call.request.local.uri
                    )
                )
                return@post
            }

            try {
                val deletedCount = cacheCleanupService.runCleanup()
                call.respond(
                    HttpStatusCode.OK,
                    CacheCleanupResponse(
                        deletedCount = deletedCount,
                        message = if (deletedCount > 0) {
                            "Deleted $deletedCount stale series from cache"
                        } else {
                            "No stale series to delete"
                        }
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Cache Cleanup Failed",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }

        /**
         * GET /api/v1/admin/cache/status
         * Get cache status including count of stale series.
         */
        get("/cache/status") {
            if (cacheCleanupService == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Cache Status Unavailable",
                        status = 503,
                        detail = "Cache cleanup service is not configured",
                        instance = call.request.local.uri
                    )
                )
                return@get
            }

            try {
                val staleCount = cacheCleanupService.getStaleCount()
                call.respond(
                    HttpStatusCode.OK,
                    CacheStatusResponse(
                        staleSeriesCount = staleCount,
                        message = if (staleCount > 0) {
                            "$staleCount series eligible for cleanup"
                        } else {
                            "No stale series in cache"
                        }
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Get Cache Status",
                        status = 500,
                        detail = e.message ?: "Unknown error",
                        instance = call.request.local.uri
                    )
                )
            }
        }
    }
}

@Serializable
data class CacheCleanupResponse(
    val deletedCount: Int,
    val message: String
)

@Serializable
data class CacheStatusResponse(
    val staleSeriesCount: Int,
    val message: String
)
