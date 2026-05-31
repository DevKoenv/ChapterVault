package dev.koenv.chaptervault.infrastructure.notifications

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry
import java.util.concurrent.ConcurrentHashMap

class DefaultNotificationChannelRegistry : NotificationChannelRegistry {
    private val channels = ConcurrentHashMap<String, NotificationChannel>()

    override fun register(channel: NotificationChannel) {
        channels[channel.typeId] = channel
    }

    override fun unregister(typeId: String) {
        channels.remove(typeId)
    }

    override fun find(typeId: String): NotificationChannel? = channels[typeId]

    override fun all(): List<NotificationChannel> = channels.values.toList()
}
