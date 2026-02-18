package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

/**
 * DAO entity for series metadata.
 *
 * @see SeriesTable
 */
class SeriesEntity(id: EntityID<UUID>) : UUIDEntity(id = id) {
    companion object : UUIDEntityClass<SeriesEntity>(SeriesTable)

    var sourceUrl by SeriesTable.sourceUrl
    var title by SeriesTable.title
    var description by SeriesTable.description
    var author by SeriesTable.author
    var coverUrl by SeriesTable.coverUrl
    var status by SeriesTable.status
    var language by SeriesTable.language
    var inLibrary by SeriesTable.inLibrary
    var addedToLibraryAt by SeriesTable.addedToLibraryAt
    var createdAt by SeriesTable.createdAt
    var updatedAt by SeriesTable.updatedAt
    var tags by SeriesTagEntity via SeriesTagsTable
}
