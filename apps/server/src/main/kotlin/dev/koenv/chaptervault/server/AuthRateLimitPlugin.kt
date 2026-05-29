package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.infrastructure.config.EndpointLimitConfig
import dev.koenv.chaptervault.infrastructure.config.RateLimitConfig
import dev.koenv.chaptervault.shared.net.CidrMatcher
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap

class AuthRateLimitPluginConfig {
    var config: RateLimitConfig = RateLimitConfig()
}

val AuthRateLimiting = createApplicationPlugin("AuthRateLimiting", ::AuthRateLimitPluginConfig) {
    val rateLimitConfig = pluginConfig.config
    if (!rateLimitConfig.enabled) return@createApplicationPlugin

    val trustedNetworkMatchers = rateLimitConfig.trustedNetworks.map { CidrMatcher(it) }
    val trustedProxyMatchers = rateLimitConfig.trustedProxies.map { CidrMatcher(it) }

    val loginLimiters = ConcurrentHashMap<String, RateLimiter>()
    val registerLimiters = ConcurrentHashMap<String, RateLimiter>()

    fun makeLimiter(cfg: EndpointLimitConfig): RateLimiter {
        val rps = cfg.maxAttempts.toDouble() / (cfg.windowMinutes * 60.0)
        return RateLimiter(requestsPerSecond = rps, burst = cfg.maxAttempts)
    }

    fun resolveClientIp(remoteAddress: String, xForwardedFor: String?): String {
        if (xForwardedFor != null && trustedProxyMatchers.any { it.matches(remoteAddress) }) {
            val ips = xForwardedFor.split(",").map { it.trim() }
            return ips.firstOrNull { ip -> trustedProxyMatchers.none { it.matches(ip) } } ?: remoteAddress
        }
        return remoteAddress
    }

    application.intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        val uri = call.request.uri.substringBefore("?")
        val isRateLimited = method == HttpMethod.Post && (uri == "/auth/login" || uri == "/auth/register")
        if (!isRateLimited) return@intercept

        val remoteAddress = call.request.local.remoteAddress
        val xff = call.request.headers["X-Forwarded-For"]
        val clientIp = resolveClientIp(remoteAddress, xff)

        if (trustedNetworkMatchers.any { it.matches(clientIp) }) return@intercept

        val (limiters, cfg) = when (uri) {
            "/auth/login" -> loginLimiters to rateLimitConfig.login
            "/auth/register" -> registerLimiters to rateLimitConfig.register
            else -> return@intercept
        }

        val limiter = limiters.getOrPut(clientIp) { makeLimiter(cfg) }
        val waitMs = limiter.tryAcquire()
        if (waitMs > 0) {
            val retryAfterSec = (waitMs / 1000) + 1
            call.response.header("Retry-After", retryAfterSec.toString())
            call.respond(HttpStatusCode.TooManyRequests)
            finish()
        }
    }
}
