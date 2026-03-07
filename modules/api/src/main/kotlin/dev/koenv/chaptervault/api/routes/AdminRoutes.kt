package dev.koenv.chaptervault.api.routes

import dev.koenv.chaptervault.api.models.ErrorTypes
import dev.koenv.chaptervault.api.models.ProblemDetail
import dev.koenv.chaptervault.orchestration.cache.CacheCleanupService
import dev.koenv.chaptervault.orchestration.engine.Orchestrator
import dev.koenv.chaptervault.orchestration.ratelimit.SiteRateLimiter
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Admin routes for system maintenance operations.
 */
fun Route.adminRoutes(
    cacheCleanupService: CacheCleanupService?,
    siteRateLimiter: SiteRateLimiter?,
    orchestrator: Orchestrator
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
         * GET /api/v1/admin/cache
         * Get cache status including count of stale series.
         */
        get("/cache") {
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

        /**
         * GET /api/v1/admin/ratelimits
         * Get current rate limiter status including site buckets and orchestrator limits.
         */
        get("/ratelimits") {
            try {
                val siteStatus = siteRateLimiter?.getStatus()
                val orchestratorStatus = orchestrator.getRateLimiterStatus()

                val siteDto = siteStatus?.let { status ->
                    SiteRateLimitStatusDto(
                        registeredConnectors = status.registeredConnectors,
                        namedBucketConfigs = status.namedBucketConfigs.map { (key, config) ->
                            key to config?.let {
                                RateLimitConfigDto(
                                    minDelayMs = it.minDelayMs,
                                    maxConcurrent = it.maxConcurrent,
                                    maxRequestsPerWindow = it.maxRequestsPerWindow,
                                    windowDurationMs = it.windowDurationMs
                                )
                            }
                        }.toMap(),
                        activeBuckets = status.activeBuckets.map { bucket ->
                            BucketStatusDto(
                                name = bucket.name,
                                maxConcurrent = bucket.maxConcurrent,
                                minDelayMs = bucket.minDelayMs,
                                maxRequestsPerWindow = bucket.maxRequestsPerWindow,
                                windowDurationMs = bucket.windowDurationMs,
                                lastRequestTime = bucket.lastRequestTime,
                                requestsInCurrentWindow = bucket.requestsInCurrentWindow,
                                backoffUntil = bucket.backoffUntil,
                                isInBackoff = bucket.isInBackoff,
                                adaptiveDelayMultiplier = bucket.adaptiveDelayMultiplier,
                                consecutive429Count = bucket.consecutive429Count
                            )
                        }
                    )
                }

                val orchestratorDto = OrchestratorRateLimitStatusDto(
                    connectors = orchestratorStatus.connectors.map { connector ->
                        ConnectorRateLimitDto(
                            connectorName = connector.connectorName,
                            maxConcurrent = connector.maxConcurrent,
                            minDelayMs = connector.minDelayMs,
                            maxRequestsPerWindow = connector.maxRequestsPerWindow,
                            windowDurationMs = connector.windowDurationMs,
                            lastRequestTime = connector.lastRequestTime,
                            requestsInCurrentWindow = connector.requestsInCurrentWindow
                        )
                    }
                )

                call.respond(
                    HttpStatusCode.OK,
                    RateLimitStatusResponse(
                        site = siteDto,
                        orchestrator = orchestratorDto
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetail(
                        type = ErrorTypes.INTERNAL_ERROR,
                        title = "Failed to Get Rate Limit Status",
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

@Serializable
data class RateLimitStatusResponse(
    val site: SiteRateLimitStatusDto?,
    val orchestrator: OrchestratorRateLimitStatusDto
)

@Serializable
data class SiteRateLimitStatusDto(
    val registeredConnectors: List<String>,
    val namedBucketConfigs: Map<String, RateLimitConfigDto?>,
    val activeBuckets: List<BucketStatusDto>
)

@Serializable
data class BucketStatusDto(
    val name: String,
    val maxConcurrent: Int,
    val minDelayMs: Long,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long,
    val lastRequestTime: Long,
    val requestsInCurrentWindow: Int,
    val backoffUntil: Long,
    val isInBackoff: Boolean,
    val adaptiveDelayMultiplier: Double,
    val consecutive429Count: Int
)

@Serializable
data class RateLimitConfigDto(
    val minDelayMs: Long,
    val maxConcurrent: Int,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long
)

@Serializable
data class OrchestratorRateLimitStatusDto(
    val connectors: List<ConnectorRateLimitDto>
)

@Serializable
data class ConnectorRateLimitDto(
    val connectorName: String,
    val maxConcurrent: Int,
    val minDelayMs: Long,
    val maxRequestsPerWindow: Int,
    val windowDurationMs: Long,
    val lastRequestTime: Long,
    val requestsInCurrentWindow: Int
)
