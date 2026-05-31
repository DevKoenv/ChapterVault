package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ExtensionRegistryTable : Table("extension_registries") {
    val id = varchar("id", 36)
    val name = varchar("name", 200)
    val url = varchar("url", 2000)
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
