package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.extension.NotificationChannel
import dev.koenv.chaptervault.kernel.extension.NotificationChannelRegistry

class TrackingNotificationRegistry(
    private val delegate: NotificationChannelRegistry,
) : NotificationChannelRegistry by delegate {
    private val _registeredTypeIds = mutableListOf<String>()
    val registeredTypeIds: List<String> get() = _registeredTypeIds.toList()

    override fun register(channel: NotificationChannel) {
        delegate.register(channel)
        _registeredTypeIds.add(channel.typeId)
    }
}
