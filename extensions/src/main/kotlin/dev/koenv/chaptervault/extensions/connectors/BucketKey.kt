package dev.koenv.chaptervault.extensions.connectors

interface BucketKey {
    /** Stable, unique identifier used in logs and diagnostics. Must not change between restarts.
     *  Map lookup uses instance equality — use `enum`, `object`, or `data class` to ensure correct behavior. */
    val id: String
}

enum class Bucket(override val id: String) : BucketKey {
    API("api"),
    CDN("cdn"),
}
