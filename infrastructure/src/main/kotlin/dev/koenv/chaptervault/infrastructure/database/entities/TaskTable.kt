package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object TaskTable : Table("tasks") {
    val id = varchar("id", 36)
    val type = varchar("type", 100)
    val status = varchar("status", 50)
    val targetType = varchar("target_type", 50)
    val targetId = varchar("target_id", 36)
    val payload = text("payload").default("{}")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)
}
