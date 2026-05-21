package dev.koenv.chaptervault.extensions.connectors

data class BucketConfig(
    val requestsPerSecond: Double,
    val burst: Int = maxOf(1, requestsPerSecond.toInt()),
)
