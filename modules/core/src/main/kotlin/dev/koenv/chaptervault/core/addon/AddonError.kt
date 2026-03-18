package dev.koenv.chaptervault.core.addon

import java.time.Instant

data class AddonError(
    val phase: String,
    val message: String,
    val stackTrace: String?,
    val occurredAt: Instant
)
