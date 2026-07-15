package dev.koenv.chaptervault.kernel.auth

import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class UserPrincipal(
    val id: Id,
    val username: String,
    val roles: Set<Role>,
    val createdAt: Instant = Instant.EPOCH,
) {
    fun hasRole(role: Role): Boolean = roles.contains(role)

    fun hasPermission(permission: Permission): Boolean = roles.any { it.permissions.contains(permission) }
}
