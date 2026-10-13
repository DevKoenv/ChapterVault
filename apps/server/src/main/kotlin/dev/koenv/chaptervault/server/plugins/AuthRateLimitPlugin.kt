@file:Suppress("MatchingDeclarationName")

package dev.koenv.chaptervault.server.plugins

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

// matched as suffixes so the plugin doesn't depend on the prefix the auth routes are mounted under (/api/v1)
private const val LOGIN_PATH_SUFFIX = "/auth/login"
private const val REGISTER_PATH_SUFFIX = "/auth/register"

class AuthRateLimitPluginConfig {
    var config: RateLimitConfig = RateLimitConfig()
}

val AuthRateLimiting =
    createApplicationPlugin("AuthRateLimiting", ::AuthRateLimitPluginConfig) {
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

        fun resolveClientIp(
            remoteAddress: String,
            xForwardedFor: String?,
        ): String {
            if (xForwardedFor != null && trustedProxyMatchers.any { it.matches(remoteAddress) }) {
                // walk from the right: every entry up to the last trusted proxy was appended by our own
                // infrastructure, while anything further left is client-controlled and can be forged
                val ips = xForwardedFor.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                return ips.lastOrNull { ip -> trustedProxyMatchers.none { it.matches(ip) } } ?: remoteAddress
            }
            return remoteAddress
        }

        application.intercept(ApplicationCallPipeline.Plugins) {
            val method = call.request.httpMethod
            val uri = call.request.uri.substringBefore("?")
            val isRateLimited =
                method == HttpMethod.Post && (uri.endsWith(LOGIN_PATH_SUFFIX) || uri.endsWith(REGISTER_PATH_SUFFIX))
            if (!isRateLimited) return@intercept

            val remoteAddress = call.request.local.remoteAddress
            val xff = call.request.headers["X-Forwarded-For"]
            val clientIp = resolveClientIp(remoteAddress, xff)

            if (trustedNetworkMatchers.any { it.matches(clientIp) }) return@intercept

            val (limiters, cfg) =
                when {
                    uri.endsWith(LOGIN_PATH_SUFFIX) -> loginLimiters to rateLimitConfig.login
                    uri.endsWith(REGISTER_PATH_SUFFIX) -> registerLimiters to rateLimitConfig.register
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
