package dev.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserTable : Table("users") {
    val id = varchar("id", 36)
    val username = varchar("username", 100).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val roles = varchar("roles", 500)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
