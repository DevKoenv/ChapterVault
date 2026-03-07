package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Table for storing series tags
 */
object TagTable : IntIdTable("tags") {
    val name = varchar("name", 64).uniqueIndex()
}
