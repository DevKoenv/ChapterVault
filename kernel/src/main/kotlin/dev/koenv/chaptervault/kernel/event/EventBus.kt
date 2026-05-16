package dev.koenv.chaptervault.kernel.event

interface EventBus {
    suspend fun publish(event: DomainEvent)
    fun subscribe(handler: suspend (DomainEvent) -> Unit)
    fun <T : DomainEvent> subscribe(eventClass: Class<T>, handler: suspend (T) -> Unit)
}

// Convenience inline extension
inline fun <reified T : DomainEvent> EventBus.on(noinline handler: suspend (T) -> Unit) {
    subscribe(T::class.java, handler)
}
