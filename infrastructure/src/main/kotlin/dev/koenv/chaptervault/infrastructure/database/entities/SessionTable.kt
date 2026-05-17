package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object SessionTable : Table("sessions") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UserTable.id)
    val token = varchar("token", 128).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
