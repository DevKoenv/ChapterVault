package dev.koenv.chaptervault.kernel.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryEventBus : EventBus {
    private val handlers = CopyOnWriteArrayList<suspend (DomainEvent) -> Unit>()
    private val typedHandlers = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<suspend (DomainEvent) -> Unit>>()

    override suspend fun publish(event: DomainEvent) {
        handlers.forEach { it(event) }
        @Suppress("UNCHECKED_CAST")
        typedHandlers[event::class.java]?.forEach { it(event) }
    }

    override fun subscribe(handler: suspend (DomainEvent) -> Unit) {
        handlers.add(handler)
    }

    override fun <T : DomainEvent> subscribe(eventClass: Class<T>, handler: suspend (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        typedHandlers.getOrPut(eventClass) { CopyOnWriteArrayList() }
            .add(handler as suspend (DomainEvent) -> Unit)
    }
}
