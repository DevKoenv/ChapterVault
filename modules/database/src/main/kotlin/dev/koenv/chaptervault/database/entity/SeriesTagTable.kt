package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Table for storing series tags
 */
object SeriesTagTable : IntIdTable("series_tag") {
    val name = varchar("name", 64).uniqueIndex()
}
