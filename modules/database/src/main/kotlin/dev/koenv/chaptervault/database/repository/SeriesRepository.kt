package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.repository.CachedSeries
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.util.UUID

/**
 * Exposed-based implementation of SeriesRepositoryPort.
 * Uses H2/SQLite/PostgreSQL via Exposed ORM.
 */
class SeriesRepository(private val database: Database) : SeriesRepositoryPort {

    override fun initialize() {
        transaction(database) {
            SchemaUtils.create(SeriesTable, SeriesTagTable, SeriesTagsTable)
        }
    }

    /**
     * Find series by source URL or return null
     */
    override fun findByUrl(url: String): CachedSeries? {
        return transaction(database) {
            val entity = SeriesEntity.find { SeriesTable.sourceUrl eq url }.firstOrNull()
            entity?.toCachedSeries()
        }
    }

    /**
     * Find series by internal ID
     */
    override fun findById(id: UUID): CachedSeries? {
        return transaction(database) {
            val entity = SeriesEntity.findById(id)
            entity?.toCachedSeries()
        }
    }

    /**
     * Save or update series metadata
     */
    override fun save(metadata: SeriesMetadata, language: String?): CachedSeries {
        return transaction(database) {
            val now = Instant.now()

            val entity = SeriesEntity.find { SeriesTable.sourceUrl eq metadata.url }.firstOrNull()
                ?: SeriesEntity.new {
                    sourceUrl = metadata.url
                    createdAt = now
                    updatedAt = now
                }

            entity.apply {
                title = metadata.title
                description = metadata.description
                author = metadata.author
                coverUrl = metadata.coverUrl
                status = metadata.status.name
                this.language = language
                updatedAt = now
            }

            // Handle tags using the junction table directly
            val existingTags = entity.tags.toList()
            val existingTagNames = existingTags.map { it.name }.toSet()
            val newTagNames = metadata.tags.toSet()

            // Remove tags that are no longer present
            val tagsToRemove = existingTags.filter { it.name !in newTagNames }
            tagsToRemove.forEach { tag ->
                SeriesTagsTable.deleteWhere {
                    (SeriesTagsTable.series eq entity.id) and (SeriesTagsTable.tag eq tag.id)
                }
            }

            // Add new tags
            val tagsToAdd = newTagNames.filter { it !in existingTagNames }
            tagsToAdd.forEach { tagName ->
                val tag = SeriesTagEntity.find { SeriesTagTable.name eq tagName }.firstOrNull()
                    ?: SeriesTagEntity.new { name = tagName }

                SeriesTagsTable.insert {
                    it[series] = entity.id
                    it[SeriesTagsTable.tag] = tag.id
                }
            }

            entity.toCachedSeries()
        }
    }

    /**
     * Get all cached series
     */
    override fun findAll(): List<CachedSeries> {
        return transaction(database) {
            SeriesEntity.all().map { it.toCachedSeries() }
        }
    }

    /**
     * Delete series by ID
     */
    override fun delete(id: UUID) {
        transaction(database) {
            val entity = SeriesEntity.findById(id) ?: return@transaction

            // Delete tag associations first
            SeriesTagsTable.deleteWhere { SeriesTagsTable.series eq entity.id }

            // Delete series
            entity.delete()
        }
    }

    private fun SeriesEntity.toCachedSeries(): CachedSeries {
        return CachedSeries(
            id = id.value,
            sourceUrl = sourceUrl,
            title = title,
            description = description,
            author = author,
            coverUrl = coverUrl,
            status = SeriesStatus.valueOf(status),
            language = language,
            tags = tags.map { it.name },
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
