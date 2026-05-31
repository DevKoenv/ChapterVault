package dev.koenv.chaptervault.kernel.extension

interface NotificationChannelRegistry {
    fun register(channel: NotificationChannel)
    fun unregister(typeId: String)
    fun find(typeId: String): NotificationChannel?
    fun all(): List<NotificationChannel>
}
