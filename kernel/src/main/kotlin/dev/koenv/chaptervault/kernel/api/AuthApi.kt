package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class Credentials(val username: String, val password: String)
data class Session(val id: Id, val userId: Id, val token: String, val expiresAt: Instant)

interface AuthApi {
    suspend fun register(credentials: Credentials, role: Role = Role.USER): Result<UserPrincipal>
    suspend fun authenticate(credentials: Credentials): Result<Pair<UserPrincipal, Session>>
    suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal>
    suspend fun validateSession(token: String): Result<UserPrincipal>
    suspend fun invalidateSession(token: String): Result<Unit>
}
