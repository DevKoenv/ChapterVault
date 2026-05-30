package dev.koenv.chaptervault.extensions.connectors

interface BucketKey {
    val id: String // stable across restarts; used as the map key
}

enum class Bucket(
    override val id: String,
) : BucketKey {
    API("api"),
    CDN("cdn"),
}
