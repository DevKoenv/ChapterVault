package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ExtensionRegistryTable
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit

class ExtensionRegistryRepository {
    fun create(
        name: String,
        url: String,
    ): ExtensionRegistryRecord {
        val id = Id.generate().toString()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        transaction {
            ExtensionRegistryTable.insert {
                it[ExtensionRegistryTable.id] = id
                it[ExtensionRegistryTable.name] = name
                it[ExtensionRegistryTable.url] = url
                it[ExtensionRegistryTable.enabled] = true
                it[ExtensionRegistryTable.createdAt] = now.toKotlinInstant()
            }
        }
        return ExtensionRegistryRecord(id = id, name = name, url = url, enabled = true, createdAt = now)
    }

    fun list(): List<ExtensionRegistryRecord> =
        transaction {
            ExtensionRegistryTable.selectAll().map { it.toRecord() }
        }

    fun findById(id: String): ExtensionRegistryRecord? =
        transaction {
            ExtensionRegistryTable
                .selectAll()
                .where { ExtensionRegistryTable.id eq id }
                .singleOrNull()
                ?.toRecord()
        }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        transaction {
            ExtensionRegistryTable.update({ ExtensionRegistryTable.id eq id }) {
                it[ExtensionRegistryTable.enabled] = enabled
            }
        }
    }

    fun delete(id: String) {
        transaction {
            ExtensionRegistryTable.deleteWhere { ExtensionRegistryTable.id eq id }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() =
        ExtensionRegistryRecord(
            id = this[ExtensionRegistryTable.id],
            name = this[ExtensionRegistryTable.name],
            url = this[ExtensionRegistryTable.url],
            enabled = this[ExtensionRegistryTable.enabled],
            createdAt = this[ExtensionRegistryTable.createdAt].toJavaInstant(),
        )
}
