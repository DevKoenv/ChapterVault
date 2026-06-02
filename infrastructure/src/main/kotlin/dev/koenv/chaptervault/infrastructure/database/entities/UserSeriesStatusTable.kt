package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserSeriesStatusTable : Table("user_series_status") {
    val userId = varchar("user_id", 36).references(UserTable.id, onDelete = ReferenceOption.CASCADE)
    val seriesId = varchar("series_id", 36).references(SeriesTable.id, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", 32)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId, seriesId)
}
