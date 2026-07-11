package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserSeriesProgressTable : Table("user_series_progress") {
    val userId = varchar("user_id", 36).references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val seriesId = varchar("series_id", 36).references(SeriesTable.id, onDelete = ReferenceOption.CASCADE)
    val latestChapterId = varchar("latest_chapter_id", 36).references(ChapterTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val latestPage = integer("latest_page").default(0)
    val readAt = timestamp("read_at")

    override val primaryKey = PrimaryKey(userId, seriesId)
}
