package dev.koenv.chaptervault.infrastructure.database.entities

import org.jetbrains.exposed.sql.Table

object ExtensionConfigTable : Table("extension_configs") {
    val extensionId = varchar("extension_id", 100)
    val key = varchar("key", 200)
    val value = text("value")

    override val primaryKey = PrimaryKey(extensionId, key)
}
