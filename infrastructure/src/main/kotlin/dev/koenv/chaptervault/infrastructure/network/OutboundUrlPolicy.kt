package dev.koenv.chaptervault.infrastructure.network

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Decides which URLs the server is willing to call on a user's behalf (notification targets). Only http and https are
 * allowed, and unless [allowLocalTargets] is set the host must not be, or resolve to, a loopback, link-local (which
 * includes the cloud metadata address 169.254.169.254) or unspecified address.
 *
 * Private LAN ranges stay allowed on purpose: a Gotify or ntfy instance on 192.168.x.x or another container is the
 * normal self-hosted setup.
 *
 * A name that does not resolve is not refused here, since it cannot be called anyway. Names are resolved again by the
 * HTTP client when it connects, so the check narrows rather than eliminates DNS rebinding.
 */
class OutboundUrlPolicy(
    private val allowLocalTargets: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    /** A short reason why [url] must not be used, or null when it is acceptable. */
    fun violation(url: String): String? {
        if (url.isBlank()) return "URL must not be blank"
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "URL is not valid"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "URL must start with http:// or https://"
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrBlank()) return "URL must contain a host"
        if (allowLocalTargets) return null

        val addresses =
            try {
                // an IP literal needs no lookup, so it is judged directly; only names go through the resolver
                if (IP_LITERAL.matches(host)) InetAddress.getAllByName(host).toList() else resolve(host)
            } catch (_: UnknownHostException) {
                return null
            }
        return if (addresses.any { isLocalOnly(it) }) "URL points at this machine or a link-local address" else null
    }

    private fun isLocalOnly(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress

    private companion object {
        val IP_LITERAL = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}\$|^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*\$")
    }
}
