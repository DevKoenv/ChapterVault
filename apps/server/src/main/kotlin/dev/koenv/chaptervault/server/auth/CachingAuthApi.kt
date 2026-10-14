package dev.koenv.chaptervault.server.auth

import dev.koenv.chaptervault.kernel.api.AuthApi
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.auth.Role
import dev.koenv.chaptervault.kernel.auth.UserPrincipal
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TTL_MS = 60_000L
private const val SWEEP_THRESHOLD = 500

/**
 * Remembers validated Basic credentials for a short time, so an OPDS reader that sends them on every request does not
 * pay for a BCrypt check each time. Whatever changes what a credential means (delete, role change, password change)
 * goes through this class, which drops the affected user's entries right away instead of waiting for the TTL.
 */
class CachingAuthApi(
    private val delegate: AuthApi,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthApi by delegate {
    private class Entry(
        val principal: UserPrincipal,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun validateCredentials(credentials: Credentials): Result<UserPrincipal> {
        val key = cacheKey(credentials)
        val now = clock()
        entries[key]?.let { if (it.expiresAt > now) return Result.Success(it.principal) }
        if (entries.size > SWEEP_THRESHOLD) entries.entries.removeIf { (_, e) -> e.expiresAt <= now }
        val result = delegate.validateCredentials(credentials)
        if (result is Result.Success) entries[key] = Entry(result.value, clock() + ttlMs)
        return result
    }

    override suspend fun deleteUser(id: Id): Result<Unit> = delegate.deleteUser(id).also { if (it is Result.Success) forget(id) }

    override suspend fun updateUserRole(
        id: Id,
        role: Role,
    ): Result<UserPrincipal> = delegate.updateUserRole(id, role).also { if (it is Result.Success) forget(id) }

    override suspend fun changePassword(
        userId: Id,
        currentPassword: String,
        newPassword: String,
    ): Result<Unit> = delegate.changePassword(userId, currentPassword, newPassword).also { if (it is Result.Success) forget(userId) }

    private fun forget(userId: Id) {
        entries.entries.removeIf { (_, e) -> e.principal.id == userId }
    }

    private fun cacheKey(credentials: Credentials): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
