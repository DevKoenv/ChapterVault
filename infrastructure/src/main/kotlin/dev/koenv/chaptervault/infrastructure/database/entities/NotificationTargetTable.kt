package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table

object NotificationTargetTable : Table("notification_targets") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val type = varchar("type", 32)
    val url = text("url")
    val token = varchar("token", 512).nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = varchar("created_at", 40)

    override val primaryKey = PrimaryKey(id)
}
