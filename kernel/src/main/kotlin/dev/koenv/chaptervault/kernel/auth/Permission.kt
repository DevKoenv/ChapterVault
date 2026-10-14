package dev.koenv.chaptervault.kernel.auth

/**
 * What a user may do. Routes check these, never roles; a [Role] is only a named bundle of permissions.
 */
enum class Permission {
    /** Browse the library, read chapters and pages, see own progress, use OPDS. */
    READ_LIBRARY,

    /** Add, edit and remove series and chapters, and browse sources to add from. */
    WRITE_LIBRARY,

    /** Record own reading progress and reading status, import a backup of it. */
    WRITE_PROGRESS,

    /** Create, list, delete users and change their roles. */
    MANAGE_USERS,

    /** List, enable, disable, reload and unload extensions. */
    MANAGE_EXTENSIONS,

    /** Create, edit, delete and test notification targets. */
    MANAGE_NOTIFICATIONS,

    /** Queue downloads and refreshes, cancel tasks. */
    TRIGGER_TASKS,

    /** See tasks, their errors and the live task event stream. */
    VIEW_TASKS,

    /** Server level information such as the update check. */
    MANAGE_SERVER,
}
