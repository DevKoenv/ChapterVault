package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * DAO entity for Series Tags
 */
class TagEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TagEntity>(TagTable)

    var name by TagTable.name
}
