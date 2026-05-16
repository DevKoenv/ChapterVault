package dev.chaptervault.kernel.auth

import dev.chaptervault.shared.utils.Id

data class UserPrincipal(
    val id: Id,
    val username: String,
    val roles: Set<Role>,
) {
    fun hasRole(role: Role): Boolean = roles.contains(role)
    fun hasPermission(permission: Permission): Boolean = roles.any { it.permissions.contains(permission) }
}
