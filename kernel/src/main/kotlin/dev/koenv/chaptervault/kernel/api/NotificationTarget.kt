package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class NotificationTarget(
    val id: Id,
    val name: String,
    val type: String,
    val url: String,
    val token: String?,
    val enabled: Boolean,
    val createdAt: Instant,
)

data class NotificationTargetInput(
    val name: String,
    val type: String,
    val url: String,
    val token: String? = null,
    val enabled: Boolean = true,
)

data class NotificationTargetPatch(
    val name: String? = null,
    val url: String? = null,
    val token: String? = null,
    val enabled: Boolean? = null,
)
