package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object SeriesTable : Table("series") {
    val id = varchar("id", 36)
    val title = varchar("title", 500)
    val connectorId = varchar("connector_id", 100)
    val externalId = varchar("external_id", 200)
    val status = varchar("status", 50)
    val autoDownload = bool("auto_download").default(false)
    val defaultFormat = varchar("default_format", 20).nullable()
    val coverUrl = varchar("cover_url", 1000).nullable()
    val description = text("description").nullable()
    val language = varchar("language", 32).default("en")
    val addedAt = timestamp("added_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
