package dev.koenv.chaptervault.config

/**
 * Logging levels used to configure minimum log severity.
 */
enum class Level(val priority: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4)
}