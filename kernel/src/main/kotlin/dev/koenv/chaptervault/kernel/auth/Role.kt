package dev.koenv.chaptervault.kernel.auth

enum class Role(
    val permissions: Set<Permission>,
) {
    ADMIN(Permission.entries.toSet()),
    USER(setOf(Permission.READ_LIBRARY, Permission.WRITE_PROGRESS, Permission.MANAGE_BOOKMARKS)),
    GUEST(setOf(Permission.READ_LIBRARY)),
}
