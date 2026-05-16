package dev.chaptervault.kernel.api

import dev.chaptervault.kernel.auth.UserPrincipal
import dev.chaptervault.shared.result.Result
import dev.chaptervault.shared.utils.Id
import java.time.Instant

data class Credentials(val username: String, val password: String)
data class Session(val id: Id, val userId: Id, val token: String, val expiresAt: Instant)

interface AuthApi {
    suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>>
    suspend fun validateSession(token: String): Result<UserPrincipal>
    suspend fun invalidateSession(token: String): Result<Unit>
}
