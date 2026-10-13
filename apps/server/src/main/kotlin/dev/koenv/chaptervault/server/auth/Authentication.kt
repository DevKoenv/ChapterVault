package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val basicAuthCache = ConcurrentHashMap<String, Pair<KtorPrincipal, Long>>()
private const val BASIC_AUTH_CACHE_TTL_MS = 60_000L

private fun basicAuthCacheKey(
    name: String,
    password: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$name:$password".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun Application.configureAuthentication(auth: AuthApi) {
    install(Authentication) {
        bearer("auth-bearer") {
            authenticate { credential ->
                when (val result = auth.validateSession(credential.token)) {
                    is Result.Success -> KtorPrincipal(result.value)
                    is Result.Failure -> null
                }
            }
        }
        basic("auth-basic") {
            realm = "ChapterVault"
            validate { credential ->
                val key = basicAuthCacheKey(credential.name, credential.password)
                val now = System.currentTimeMillis()
                val cached = basicAuthCache[key]
                if (cached != null && cached.second > now) {
                    return@validate cached.first
                }
                if (basicAuthCache.size > 500) {
                    basicAuthCache.entries.removeIf { (_, v) -> v.second <= now }
                }
                when (val result = auth.validateCredentials(Credentials(credential.name, credential.password))) {
                    is Result.Success ->
                        KtorPrincipal(result.value).also {
                            basicAuthCache[key] = it to (System.currentTimeMillis() + BASIC_AUTH_CACHE_TTL_MS)
                        }
                    is Result.Failure -> null
                }
            }
        }
    }
}
