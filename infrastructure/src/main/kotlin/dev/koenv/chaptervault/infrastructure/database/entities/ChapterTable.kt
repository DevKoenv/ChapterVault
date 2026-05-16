package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ChapterTable : Table("chapters") {
    val id = varchar("id", 36)
    val seriesId = varchar("series_id", 36).references(SeriesTable.id)
    val title = varchar("title", 500)
    val chapterIndex = double("chapter_index")
    val externalId = varchar("external_id", 200)
    val status = varchar("status", 50)
    val format = varchar("format", 20).nullable()
    val pageCount = integer("page_count").nullable()
    val addedAt = timestamp("added_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
