package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object BookmarkTable : Table("bookmarks") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UserTable.id)
    val chapterId = varchar("chapter_id", 36).references(ChapterTable.id, onDelete = ReferenceOption.CASCADE)
    val page = integer("page")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
