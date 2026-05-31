package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table

object SeriesGenresTable : Table("series_genres") {
    val seriesId = varchar("series_id", 36).references(SeriesTable.id)
    val genre = varchar("genre", 100)

    override val primaryKey = PrimaryKey(seriesId, genre)
}
