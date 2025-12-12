// kotlin
package dev.koenv.chaptervault.core.eventbus

import dev.koenv.chaptervault.core.eventbus.events.UnhandledExceptionEvent
import kotlin.reflect.KClass
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal, robust EventBus for ChapterVault core.
 *
 * - subscribe/emit/post API (inline reified subscribe)
 * - thread-safe
 * - coroutine-friendly
 * - supports listeners registered for supertypes/interfaces
 * - isolates listener exceptions and continues delivering
 * - emits UnhandledExceptionEvent
 *
 * After shutdown, you can call init() again to reuse.
 */
object EventBus {
    private val listeners = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<suspend (Any) -> Unit>>()

    @Volatile
    private var scope = createScope()
    private val initialized = AtomicBoolean(false)

    private fun createScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Initialize or reinitialize the internal coroutine scope so the bus can be reused after shutdown.
     */
    fun init() {
        if (initialized.compareAndSet(false, true)) {
            scope = createScope()
        }
    }

    /**
     * Shutdown the bus: cancel internal scope and remove listeners.
     * After shutdown, you can call init() again to reuse.
     */
    suspend fun shutdown() {
        if (initialized.compareAndSet(true, false)) {
            val s = scope
            s.coroutineContext[Job]?.cancelAndJoin()
            listeners.clear()
        }
    }

    private fun ensureInitialized() {
        if (!initialized.get()) {
            throw IllegalStateException("EventBus not initialized. Call EventBus.init() before use.")
        }
    }

    /**
     * Subscribe using reified type parameter for ergonomics:
     * val event = EventBus.subscribe<AppStartupEvent> { ev -> ... }
     */
    inline fun <reified T : Any> subscribe(
        noinline handler: suspend (T) -> Unit
    ): ListenerHandle = subscribe(T::class, handler)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> subscribe(
        clazz: KClass<T>,
        handler: suspend (T) -> Unit
    ): ListenerHandle {
        ensureInitialized()
        val wrapper: suspend (Any) -> Unit = { event -> handler(event as T) }
        listeners.computeIfAbsent(clazz) { CopyOnWriteArrayList() }.add(wrapper)
        return ListenerHandle { listeners[clazz]?.remove(wrapper) }
    }

    /**
     * Subscribe to every event regardless of type.
     *
     * Useful for debugging/logging where you want a catch-all listener.
     *
     * Example:
     * val handle = EventBus.subscribeToAll { ev -> println("saw event: $ev") }
     * ...
     * EventBus.unsubscribe(handle)
     */
    fun subscribeToAll(handler: suspend (Any) -> Unit): ListenerHandle =
        subscribe(Any::class, handler)

    /**
     * Unsubscribe by handle.
     */
    fun unsubscribe(handle: ListenerHandle) {
        ensureInitialized()
        handle.unregister()
    }

    /**
     * Fire-and-forget: handlers are launched on internal scope and not awaited.
     */
    fun post(event: Any) {
        ensureInitialized()
        scope.launch { dispatch(event, wait = false) }
    }

    /**
     * Suspend until all handlers complete.
     */
    suspend fun emit(event: Any) {
        ensureInitialized()
        return dispatch(event, wait = true)
    }

    /**
     * Blocking variant that waits for all handlers on the calling thread.
     *
     * WARNING: Do NOT call from inside an existing coroutine (runBlocking will throw).
     * Use emit from coroutines instead.
     */
    fun postBlocking(event: Any) {
        ensureInitialized()
        runBlocking { dispatch(event, wait = true, forceBlocking = true) }
    }

    private suspend fun dispatch(event: Any, wait: Boolean, forceBlocking: Boolean = false) {
        // collect listeners whose registered class isInstance(event), allows supertypes & interfaces
        val snapshotKeys = listeners.keys().toList()
        val matched = snapshotKeys.flatMap { key ->
            if (key.java.isInstance(event)) listeners[key]?.toList().orEmpty() else emptyList()
        }
        if (matched.isEmpty()) return

        val errors = ConcurrentLinkedQueue<Throwable>()

        if (wait || forceBlocking) {
            if (forceBlocking) {
                // Run handlers sequentially on current thread to avoid spawning coroutines
                matched.forEach { handler ->
                    try {
                        // handler is `suspend`; already in coroutine context, just call directly
                        handler(event)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            } else {
                // structured concurrency: wait for all handlers, exceptions isolated per handler
                coroutineScope {
                    matched.forEach { handler ->
                        launch {
                            try {
                                handler(event)
                            } catch (t: Throwable) {
                                errors.add(t)
                            }
                        }
                    }
                }
            }
        } else {
            // fire-and-forget
            matched.forEach { handler ->
                scope.launch {
                    try {
                        handler(event)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }

        if (errors.isEmpty().not() && event !is UnhandledExceptionEvent) {
            // post errors as an event (fire-and-forget) but avoid recursion.
            post(UnhandledExceptionEvent(event, errors.toList()))
        }
    }
}