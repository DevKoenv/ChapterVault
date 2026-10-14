package dev.koenv.chaptervault.kernel.api

/** Paths of the public API that are also stored or linked outside the routing code (cover URLs in the database, OPDS). */
object ApiPaths {
    const val V1 = "/api/v1"

    fun seriesCover(seriesId: String): String = "$V1/library/series/$seriesId/cover"
}
