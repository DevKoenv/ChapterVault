package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID as JavaUUID

/**
 * Repository for series metadata caching
 * Stores static information that doesn't change frequently
 */
class SeriesRepository(private val database: Database) {
    
    fun initialize() {
        transaction(database) {
            SchemaUtils.create(SeriesTable, SeriesTagTable, SeriesTagsTable)
        }
    }
    
    /**
     * Find series by source URL or return null
     */
    fun findByUrl(url: String): CachedSeries? {
        return transaction(database) {
            val entity = SeriesEntity.find { SeriesTable.sourceUrl eq url }.firstOrNull()
            entity?.toCachedSeries()
        }
    }
    
    /**
     * Find series by internal ID
     */
    fun findById(id: JavaUUID): CachedSeries? {
        return transaction(database) {
            val entity = SeriesEntity.findById(id)
            entity?.toCachedSeries()
        }
    }
    
    /**
     * Save or update series metadata
     */
    fun save(metadata: SeriesMetadata, language: String? = null): CachedSeries {
        return transaction(database) {
            val now = Instant.now()
            
            val entity = SeriesEntity.find { SeriesTable.sourceUrl eq metadata.url }.firstOrNull()
                ?: SeriesEntity.new {
                    sourceUrl = metadata.url
                    createdAt = now
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
            
            // Handle tags
            val existingTags = entity.tags.toList()
            val tagNames = metadata.tags.toSet()
            
            // Remove tags that are no longer present
            existingTags.filter { it.name !in tagNames }.forEach { tag ->
                entity.tags = entity.tags.minus(tag)
            }
            
            // Add new tags
            tagNames.forEach { tagName ->
                val tag = SeriesTagEntity.find { SeriesTagTable.name eq tagName }.firstOrNull()
                    ?: SeriesTagEntity.new { name = tagName }
                if (tag !in entity.tags) {
                    entity.tags = entity.tags.plus(tag)
                }
            }
            
            entity.toCachedSeries()
        }
    }
    
    /**
     * Get all cached series
     */
    fun findAll(): List<CachedSeries> {
        return transaction(database) {
            SeriesEntity.all().map { it.toCachedSeries() }
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

/**
 * Cached series model with internal UUID
 */
data class CachedSeries(
    val id: JavaUUID,
    val sourceUrl: String,
    val title: String,
    val description: String?,
    val author: String?,
    val coverUrl: String?,
    val status: SeriesStatus,
    val language: String?,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant
)
