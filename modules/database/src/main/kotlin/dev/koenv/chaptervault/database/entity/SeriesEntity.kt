package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.UUID

/**
 * DAO entity for Series
 */
class SeriesEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<SeriesEntity>(SeriesTable)
    
    var sourceUrl by SeriesTable.sourceUrl
    var title by SeriesTable.title
    var description by SeriesTable.description
    var author by SeriesTable.author
    var coverUrl by SeriesTable.coverUrl
    var status by SeriesTable.status
    var language by SeriesTable.language
    var createdAt by SeriesTable.createdAt
    var updatedAt by SeriesTable.updatedAt
    var tags by SeriesTagEntity via SeriesTagsTable
}
