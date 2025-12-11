package dev.koenv.chaptervault.core.eventbus

/**
 * Small handle returned by subscribe(). Call .unregister() to remove the listener.
 */
class ListenerHandle(private val action: () -> Unit) {
    fun unregister() = action()
}