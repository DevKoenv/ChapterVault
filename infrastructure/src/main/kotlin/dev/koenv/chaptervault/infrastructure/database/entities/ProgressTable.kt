package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ProgressTable : Table("progress") {
    val userId = varchar("user_id", 36).references(UserTable.id)
    val chapterId = varchar("chapter_id", 36).references(ChapterTable.id)
    val readAt = timestamp("read_at")

    override val primaryKey = PrimaryKey(userId, chapterId)
}
