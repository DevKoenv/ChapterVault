package dev.koenv.chaptervault.extensions.connectors

interface BucketKey {
    /** Stable, unique identifier used as a map key and in logs/diagnostics. Must not change between restarts. */
    val id: String
}

enum class Bucket(override val id: String) : BucketKey {
    API("api"),
    CDN("cdn"),
}
