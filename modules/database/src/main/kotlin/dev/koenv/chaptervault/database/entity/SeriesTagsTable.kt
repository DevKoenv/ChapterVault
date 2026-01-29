package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.Table

/**
 * Many-to-many relationship table between Series and Tags
 */
object SeriesTagsTable : Table("series_tags") {
    val series = reference("series_id", SeriesTable)
    val tag = reference("tag_id", SeriesTagTable)
    
    override val primaryKey = PrimaryKey(series, tag)
}
