package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table

object UserSeriesStatusTable : Table("user_series_status") {
    val userId = varchar("user_id", 36)
    val seriesId = varchar("series_id", 36)
    val status = varchar("status", 32)
    val updatedAt = varchar("updated_at", 40)

    override val primaryKey = PrimaryKey(userId, seriesId)
}
