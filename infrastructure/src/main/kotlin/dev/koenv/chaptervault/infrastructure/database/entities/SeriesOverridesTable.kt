package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object SeriesOverridesTable : Table("series_overrides") {
    val seriesId = varchar("series_id", 36).references(SeriesTable.id, onDelete = ReferenceOption.CASCADE)
    val field = varchar("field", 50)
    val value = text("value")

    override val primaryKey = PrimaryKey(seriesId, field)
}
