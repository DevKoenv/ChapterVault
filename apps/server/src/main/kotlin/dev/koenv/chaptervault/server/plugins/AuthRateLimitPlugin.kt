@file:Suppress("MatchingDeclarationName")

package dev.koenv.chaptervault.server.plugins

import dev.koenv.chaptervault.infrastructure.config.EndpointLimitConfig
import dev.koenv.chaptervault.infrastructure.config.RateLimitConfig
import dev.koenv.chaptervault.shared.net.CidrMatcher
import dev.koenv.chaptervault.shared.ratelimit.RateLimiter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

// matched as suffixes so the plugin doesn't depend on the prefix the auth routes are mounted under (/api/v1)
private const val LOGIN_PATH_SUFFIX = "/auth/login"
private const val REGISTER_PATH_SUFFIX = "/auth/register"

private const val DEFAULT_SWEEP_THRESHOLD = 1_000
private const val DEFAULT_SWEEP_INTERVAL_MS = 60_000L
private const val DEFAULT_MAX_ENTRIES = 10_000
private const val IPV6_PREFIX_BYTES = 8 // /64

private val logger = KotlinLogging.logger {}

class AuthRateLimitPluginConfig {
    var config: RateLimitConfig = RateLimitConfig()

    /** Time source for limiter eviction, replaceable in tests. */
    var clock: () -> Long = System::currentTimeMillis

    internal var sweepThreshold: Int = DEFAULT_SWEEP_THRESHOLD

    internal var maxEntries: Int = DEFAULT_MAX_ENTRIES
}

/**
 * Key under which a client is tracked. IPv6 clients are keyed by their /64 prefix, because a single host or
 * subscriber usually controls a whole /64 and could otherwise rotate through addresses to dodge the limit.
 * IPv4 addresses (and anything that is not an IPv6 literal) are used as they are.
 */
internal fun limiterKey(clientIp: String): String {
    if (':' !in clientIp) return clientIp
    // only literals contain ':' so this never triggers a DNS lookup
    val parsed = runCatching { InetAddress.getByName(clientIp) }.getOrNull()
    // IPv4-mapped IPv6 addresses come back as Inet4Address
    val address = parsed as? Inet6Address ?: return (parsed as? Inet4Address)?.hostAddress ?: clientIp
    val prefix = address.address.copyOf(IPV6_PREFIX_BYTES)
    return prefix.toList().chunked(2).joinToString(":", postfix = "::/64") { (hi, lo) ->
        "%02x%02x".format(hi, lo)
    }
}

/**
 * Per-client [RateLimiter]s with eviction. A client that has been idle for longer than the limiter's window has an
 * empty limiter anyway, so dropping it is equivalent to keeping it. Idle entries are swept once the map grows past
 * [sweepThreshold], at most once per [sweepIntervalMs].
 *
 * At most [maxEntries] clients are tracked. When the map is full, idle entries are swept first; if it is still
 * full, new clients share a single overflow limiter instead of getting their own. That keeps memory bounded without
 * locking anybody out entirely, and caps the combined attempts of untracked clients at one client's budget.
 */
internal class ClientLimiters(
    private val idleTimeoutMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sweepThreshold: Int = DEFAULT_SWEEP_THRESHOLD,
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private class Entry(
        val limiter: RateLimiter,
        @Volatile var lastAccess: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var lastSweep = clock()

    private var overflow: RateLimiter? = null

    val size: Int get() = entries.size

    fun get(
        clientIp: String,
        create: () -> RateLimiter,
    ): RateLimiter {
        val key = limiterKey(clientIp)
        val now = clock()
        sweepIfNeeded(now)
        entries[key]?.let {
            it.lastAccess = now
            return it.limiter
        }
        // creating entries is serialized so the cap is never exceeded
        synchronized(this) {
            entries[key]?.let {
                it.lastAccess = now
                return it.limiter
            }
            if (entries.size >= maxEntries) sweep(now)
            if (entries.size >= maxEntries) return overflow ?: create().also { overflow = it }
            return Entry(create(), now).also { entries[key] = it }.limiter
        }
    }

    private fun sweepIfNeeded(now: Long) {
        if (entries.size < sweepThreshold || now - lastSweep < sweepIntervalMs) return
        sweep(now)
    }

    private fun sweep(now: Long) {
        lastSweep = now
        entries.entries.removeIf { (_, entry) -> now - entry.lastAccess > idleTimeoutMs }
    }
}

/**
 * Without trusted proxies the socket address is the client address, so behind a reverse proxy every request
 * comes from the proxy: either exempt (private or loopback trusted networks) or sharing a single bucket.
 */
internal fun shouldWarnAboutProxyMisconfiguration(config: RateLimitConfig): Boolean =
    config.enabled && config.trustedProxies.isEmpty() && config.trustedNetworks.any { isPrivateOrLoopbackRange(it) }

private fun isPrivateOrLoopbackRange(cidr: String): Boolean {
    val address = runCatching { InetAddress.getByName(cidr.substringBefore('/')) }.getOrNull() ?: return false
    val isUniqueLocalIpv6 = address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC
    return address.isLoopbackAddress ||
        address.isSiteLocalAddress ||
        address.isLinkLocalAddress ||
        address.isAnyLocalAddress ||
        isUniqueLocalIpv6
}

val AuthRateLimiting =
    createApplicationPlugin("AuthRateLimiting", ::AuthRateLimitPluginConfig) {
        val rateLimitConfig = pluginConfig.config
        if (!rateLimitConfig.enabled) return@createApplicationPlugin

        if (shouldWarnAboutProxyMisconfiguration(rateLimitConfig)) {
            logger.warn {
                "Auth rate limiting has no trustedProxies configured while trustedNetworks contains private or " +
                    "loopback ranges. Behind a reverse proxy every request will look exempt (or share the proxy's " +
                    "IP) until trustedProxies is set and trustedNetworks is narrowed."
            }
        }

        val trustedNetworkMatchers = rateLimitConfig.trustedNetworks.map { CidrMatcher(it) }
        val trustedProxyMatchers = rateLimitConfig.trustedProxies.map { CidrMatcher(it) }

        fun clientLimiters(cfg: EndpointLimitConfig) =
            ClientLimiters(
                idleTimeoutMs = cfg.windowMinutes * 60_000L,
                clock = pluginConfig.clock,
                sweepThreshold = pluginConfig.sweepThreshold,
                maxEntries = pluginConfig.maxEntries,
            )
        val loginLimiters = clientLimiters(rateLimitConfig.login)
        val registerLimiters = clientLimiters(rateLimitConfig.register)

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

            val limiter = limiters.get(clientIp) { makeLimiter(cfg) }
            val waitMs = limiter.tryAcquire()
            if (waitMs > 0) {
                val retryAfterSec = (waitMs / 1000) + 1
                call.response.header("Retry-After", retryAfterSec.toString())
                call.respond(HttpStatusCode.TooManyRequests)
                finish()
            }
        }
    }
