package dev.koenv.chaptervault.core.eventbus.events

/**
 * Emitted when one or more listeners threw during handling an event.
 * Note: EventBus avoids re-emitting these recursively.
 */
data class UnhandledExceptionEvent(
    val sourceEvent: Any,
    val exceptions: List<Throwable>
)