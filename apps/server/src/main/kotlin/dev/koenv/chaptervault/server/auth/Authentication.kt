package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.interfaces.api.rest.KtorPrincipal
import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.server.plugins.BasicAuthThrottleKey
import dev.koenv.chaptervault.shared.result.Result
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer

/**
 * @param auth should be the [CachingAuthApi]-wrapped one so Basic credentials are not re-checked on every request.
 */
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
                when (val result = auth.validateCredentials(Credentials(credential.name, credential.password))) {
                    is Result.Success -> KtorPrincipal(result.value)
                    is Result.Failure -> {
                        application.attributes.getOrNull(BasicAuthThrottleKey)?.recordFailure(this, credential.name)
                        null
                    }
                }
            }
        }
    }
}
