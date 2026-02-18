package dev.koenv.chaptervault.database.repository

import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.repository.Series
import dev.koenv.chaptervault.core.repository.ChapterRepositoryPort
import dev.koenv.chaptervault.core.repository.SeriesRepositoryPort
import dev.koenv.chaptervault.database.entity.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Exposed-based implementation of SeriesRepositoryPort.
 * Uses H2/SQLite/PostgreSQL via Exposed ORM.
 */
class SeriesRepository(private val database: Database) : SeriesRepositoryPort {

    private val logger = LoggerFactory.getLogger(SeriesRepository::class.java)

    override fun initialize() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(SeriesTable, SeriesTagTable, SeriesTagsTable)
        }
    }

    /**
     * Runs data migrations to ensure data consistency after schema changes.
     * Call this after all repositories are initialized.
     *
     * @param chapterRepository Required to check which series have downloaded chapters
     */
    fun runMigrations(chapterRepository: ChapterRepositoryPort) {
        transaction(database) {
            migrateExistingDownloadsToLibrary(chapterRepository)
        }
    }

    /**
     * Marks existing series that have downloaded chapters as library items.
     * This ensures backwards compatibility after adding the inLibrary flag.
     */
    private fun migrateExistingDownloadsToLibrary(chapterRepository: ChapterRepositoryPort) {
        val allSeries = SeriesEntity.all().toList()
        var migratedCount = 0

        allSeries.forEach { series ->
            if (!series.inLibrary) {
                val downloadedCount = chapterRepository.countDownloaded(series.id.value)
                if (downloadedCount > 0) {
                    series.inLibrary = true
                    series.addedToLibraryAt = series.createdAt
                    migratedCount++
                }
            }
        }

        if (migratedCount > 0) {
            logger.info("Migrated {} existing series with downloads to library", migratedCount)
        }
    }

    override fun findByUrl(url: String): Series? {
        return transaction(database) {
            val entity = SeriesEntity.find { SeriesTable.sourceUrl eq url }.firstOrNull()
            entity?.toSeries()
        }
    }

    override fun findById(id: UUID): Series? {
        return transaction(database) {
            val entity = SeriesEntity.findById(id)
            entity?.toSeries()
        }
    }

    /**
     * Upsert series from full metadata using merge semantics.
     * Non-null values always win — existing non-null fields are never overwritten with null.
     */
    override fun upsert(metadata: SeriesMetadata, language: String?): Series {
        return transaction(database) {
            val now = Instant.now()

            val existing = SeriesEntity.find { SeriesTable.sourceUrl eq metadata.url }.firstOrNull()
            val entity = existing ?: SeriesEntity.new {
                sourceUrl = metadata.url
                createdAt = now
                updatedAt = now
                inLibrary = false
            }

            entity.apply {
                title = metadata.title
                description = metadata.description ?: existing?.description
                author = metadata.author ?: existing?.author
                coverUrl = metadata.coverUrl ?: existing?.coverUrl
                status = metadata.status.name
                this.language = language ?: existing?.language
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

            entity.toSeries()
        }
    }

    override fun findAll(): List<Series> {
        return transaction(database) {
            SeriesEntity.all().map { it.toSeries() }
        }
    }

    override fun delete(id: UUID) {
        transaction(database) {
            val entity = SeriesEntity.findById(id) ?: return@transaction

            // Delete tag associations first
            SeriesTagsTable.deleteWhere { SeriesTagsTable.series eq entity.id }

            // Delete series
            entity.delete()
        }
    }

    /**
     * Upsert a series from search results using merge semantics.
     * Non-null values always win — existing non-null fields are never overwritten with null.
     */
    override fun upsertFromSearch(result: SeriesSearchResult): Series {
        return transaction(database) {
            val now = Instant.now()

            val existing = SeriesEntity.find { SeriesTable.sourceUrl eq result.url }.firstOrNull()

            if (existing != null) {
                // Merge: only overwrite nullable fields if the new value is non-null
                existing.apply {
                    title = result.title
                    description = result.description ?: existing.description
                    coverUrl = result.coverUrl ?: existing.coverUrl
                    updatedAt = now
                }
                return@transaction existing.toSeries()
            }

            val entity = SeriesEntity.new {
                sourceUrl = result.url
                title = result.title
                description = result.description
                author = null
                coverUrl = result.coverUrl
                status = SeriesStatus.UNKNOWN.name
                language = null
                createdAt = now
                updatedAt = now
                inLibrary = false
                addedToLibraryAt = null
            }

            entity.toSeries()
        }
    }

    override fun upsertAllFromSearch(results: List<SeriesSearchResult>): List<Series> {
        return transaction(database) {
            results.map { result ->
                val now = Instant.now()

                val existing = SeriesEntity.find { SeriesTable.sourceUrl eq result.url }.firstOrNull()

                if (existing != null) {
                    existing.apply {
                        title = result.title
                        description = result.description ?: existing.description
                        coverUrl = result.coverUrl ?: existing.coverUrl
                        updatedAt = now
                    }
                    return@map existing.toSeries()
                }

                val entity = SeriesEntity.new {
                    sourceUrl = result.url
                    title = result.title
                    description = result.description
                    author = null
                    coverUrl = result.coverUrl
                    status = SeriesStatus.UNKNOWN.name
                    language = null
                    createdAt = now
                    updatedAt = now
                    inLibrary = false
                    addedToLibraryAt = null
                }

                entity.toSeries()
            }
        }
    }

    override fun findStaleCache(olderThan: Instant, excludeLibrary: Boolean): List<Series> {
        return transaction(database) {
            val query = if (excludeLibrary) {
                SeriesEntity.find {
                    (SeriesTable.updatedAt less olderThan) and (SeriesTable.inLibrary eq false)
                }
            } else {
                SeriesEntity.find { SeriesTable.updatedAt less olderThan }
            }
            query.map { it.toSeries() }
        }
    }

    override fun deleteStaleCache(olderThan: Instant): Int {
        return transaction(database) {
            val staleEntities = SeriesEntity.find {
                (SeriesTable.updatedAt less olderThan) and (SeriesTable.inLibrary eq false)
            }.toList()

            var deleted = 0
            staleEntities.forEach { entity ->
                // Delete tag associations first
                SeriesTagsTable.deleteWhere { SeriesTagsTable.series eq entity.id }
                entity.delete()
                deleted++
            }

            deleted
        }
    }

    override fun addToLibrary(id: UUID): Series {
        return transaction(database) {
            val entity = SeriesEntity.findById(id)
                ?: throw IllegalArgumentException("Series not found: $id")

            val now = Instant.now()
            entity.inLibrary = true
            entity.addedToLibraryAt = now
            entity.updatedAt = now

            entity.toSeries()
        }
    }

    override fun removeFromLibrary(id: UUID): Series {
        return transaction(database) {
            val entity = SeriesEntity.findById(id)
                ?: throw IllegalArgumentException("Series not found: $id")

            val now = Instant.now()
            entity.inLibrary = false
            entity.addedToLibraryAt = null
            entity.updatedAt = now

            entity.toSeries()
        }
    }

    override fun findAllInLibrary(): List<Series> {
        return transaction(database) {
            SeriesEntity.find { SeriesTable.inLibrary eq true }.map { it.toSeries() }
        }
    }

    private fun SeriesEntity.toSeries(): Series {
        return Series(
            id = id.value,
            sourceUrl = sourceUrl,
            title = title,
            description = description,
            author = author,
            coverUrl = coverUrl,
            status = SeriesStatus.valueOf(status),
            language = language,
            tags = tags.map { it.name },
            inLibrary = inLibrary,
            addedToLibraryAt = addedToLibraryAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
