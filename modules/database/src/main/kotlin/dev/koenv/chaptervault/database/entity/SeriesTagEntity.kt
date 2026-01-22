package dev.koenv.chaptervault.database.entity

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

/**
 * DAO entity for Series Tags
 */
class SeriesTagEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SeriesTagEntity>(SeriesTagTable)
    
    var name by SeriesTagTable.name
}
