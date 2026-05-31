package dev.koenv.chaptervault.kernel.connector

data class BucketConfig(
    val requestsPerSecond: Double,
    val burst: Int = maxOf(1, requestsPerSecond.toInt()),
) {
    init {
        require(requestsPerSecond > 0.0) { "requestsPerSecond must be > 0.0, got $requestsPerSecond" }
        require(burst >= 1) { "burst must be >= 1, got $burst" }
    }
}
