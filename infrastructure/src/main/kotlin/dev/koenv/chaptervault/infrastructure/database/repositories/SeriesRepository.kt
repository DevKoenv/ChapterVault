package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesGenresTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class SeriesRepository(
    private val fileStorage: FileStorage,
) : LibraryReadApi,
    LibraryCommandApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun getSeries(id: Id): Result<Series> =
        dbQuery {
            val row =
                SeriesTable
                    .selectAll()
                    .where { SeriesTable.id eq id.toString() }
                    .singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("Series", id.toString()))
            Result.Success(row.toSeries(genresFor(id.toString())))
        }

    override suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> =
        dbQuery {
            val total = SeriesTable.selectAll().count()
            val rows =
                SeriesTable
                    .selectAll()
                    .orderBy(SeriesTable.title to SortOrder.ASC, SeriesTable.id to SortOrder.ASC)
                    .limit(request.size, (request.page * request.size).toLong())
                    .toList()
            val ids = rows.map { it[SeriesTable.id] }
            val genreMap = genresForIds(ids)
            val items = rows.map { it.toSeries(genreMap[it[SeriesTable.id]].orEmpty()) }
            Result.Success(Pagination(items, request.page, request.size, total))
        }

    override suspend fun listSeriesByIds(
        ids: Collection<Id>,
        request: PageRequest,
    ): Result<Pagination<Series>> =
        dbQuery {
            if (ids.isEmpty()) return@dbQuery Result.Success(Pagination(emptyList(), request.page, request.size, 0L))
            val idStrings = ids.map { it.toString() }
            val total = SeriesTable.selectAll().where { SeriesTable.id inList idStrings }.count()
            val rows =
                SeriesTable
                    .selectAll()
                    .where { SeriesTable.id inList idStrings }
                    .orderBy(SeriesTable.title to SortOrder.ASC, SeriesTable.id to SortOrder.ASC)
                    .limit(request.size, (request.page * request.size).toLong())
                    .toList()
            val rowIds = rows.map { it[SeriesTable.id] }
            val genreMap = genresForIds(rowIds)
            val items = rows.map { it.toSeries(genreMap[it[SeriesTable.id]].orEmpty()) }
            Result.Success(Pagination(items, request.page, request.size, total))
        }

    override suspend fun searchLibrary(
        query: String,
        request: PageRequest,
    ): Result<Pagination<Series>> =
        dbQuery {
            // SQLite has no default LIKE escape character, so the escape has to be declared (ESCAPE '\')
            val pattern = LikePattern("%" + LikePattern.ofLiteral(query).pattern + "%", '\\')
            val total = SeriesTable.selectAll().where { SeriesTable.title like pattern }.count()
            val rows =
                SeriesTable
                    .selectAll()
                    .where { SeriesTable.title like pattern }
                    .orderBy(SeriesTable.title to SortOrder.ASC, SeriesTable.id to SortOrder.ASC)
                    .limit(request.size, (request.page * request.size).toLong())
                    .toList()
            val ids = rows.map { it[SeriesTable.id] }
            val genreMap = genresForIds(ids)
            val items = rows.map { it.toSeries(genreMap[it[SeriesTable.id]].orEmpty()) }
            Result.Success(Pagination(items, request.page, request.size, total))
        }

    override suspend fun getChapter(id: Id): Result<Chapter> =
        dbQuery {
            ChapterTable
                .selectAll()
                .where { ChapterTable.id eq id.toString() }
                .singleOrNull()
                ?.toChapter()
                ?.let { Result.Success(it) }
                ?: Result.Failure(AppError.NotFound("Chapter", id.toString()))
        }

    override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> =
        dbQuery {
            val chapters =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.seriesId eq seriesId.toString() }
                    .orderBy(ChapterTable.chapterIndex, SortOrder.ASC)
                    .map { it.toChapter() }
            Result.Success(chapters)
        }

    override suspend fun listChaptersByStatus(
        seriesId: Id,
        status: DownloadStatus,
    ): Result<List<Chapter>> =
        dbQuery {
            val chapters =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.seriesId eq seriesId.toString()) and (ChapterTable.downloadStatus eq status.name) }
                    .orderBy(ChapterTable.chapterIndex, SortOrder.ASC)
                    .map { it.toChapter() }
            Result.Success(chapters)
        }

    override suspend fun listChaptersPaginated(
        seriesId: Id,
        status: DownloadStatus?,
        request: PageRequest,
    ): Result<Pagination<Chapter>> =
        dbQuery {
            val condition =
                if (status != null) {
                    (ChapterTable.seriesId eq seriesId.toString()) and (ChapterTable.downloadStatus eq status.name)
                } else {
                    ChapterTable.seriesId eq seriesId.toString()
                }
            val total = ChapterTable.selectAll().where(condition).count()
            val chapters =
                ChapterTable
                    .selectAll()
                    .where(condition)
                    .orderBy(ChapterTable.chapterIndex to SortOrder.ASC, ChapterTable.id to SortOrder.ASC)
                    .limit(request.size, (request.page * request.size).toLong())
                    .map { it.toChapter() }
            Result.Success(Pagination(chapters, request.page, request.size, total))
        }

    override suspend fun addToLibrary(
        connectorId: String,
        externalId: String,
        language: String,
        autoDownload: Boolean,
    ): Result<Series> =
        dbQuery {
            val existing =
                SeriesTable
                    .selectAll()
                    .where {
                        (SeriesTable.connectorId eq connectorId) and (SeriesTable.externalId eq externalId) and
                            (SeriesTable.language eq language)
                    }.singleOrNull()

            if (existing != null) {
                return@dbQuery Result.Failure(AppError.Conflict("Series '$externalId' (language='$language') already in library"))
            }

            val id = Id.generate()
            val now = Instant.now().toKotlinInstant()
            SeriesTable.insert {
                it[SeriesTable.id] = id.toString()
                it[SeriesTable.title] = externalId
                it[SeriesTable.connectorId] = connectorId
                it[SeriesTable.externalId] = externalId
                it[SeriesTable.language] = language
                it[SeriesTable.status] = SeriesStatus.IN_LIBRARY.name
                it[SeriesTable.autoDownload] = autoDownload
                it[SeriesTable.defaultFormat] = null
                it[SeriesTable.coverUrl] = null
                it[SeriesTable.description] = null
                it[SeriesTable.addedAt] = now
                it[SeriesTable.updatedAt] = now
            }

            Result.Success(
                SeriesTable
                    .selectAll()
                    .where { SeriesTable.id eq id.toString() }
                    .single()
                    .toSeries(),
            )
        } // genres are empty for a newly added series — no enrichment has run yet

    override suspend fun evictChapter(id: Id): Result<Unit> {
        val chapterData =
            dbQuery {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq id.toString() }
                    .singleOrNull()
                    ?.let { it[ChapterTable.seriesId] to it[ChapterTable.id] }
            } ?: return Result.Failure(AppError.NotFound("Chapter", id.toString()))

        val (seriesId, chapterId) = chapterData
        dbQuery {
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                it[downloadStatus] = DownloadStatus.AVAILABLE.name
                it[format] = null
                it[pageCount] = null
                it[updatedAt] = Instant.now().toKotlinInstant()
            }
        }
        fileStorage.deleteChapterFiles(seriesId, chapterId)
        return Result.Success(Unit)
    }

    override suspend fun markChapterPending(id: Id): Result<Unit> =
        dbQuery {
            val updated =
                ChapterTable.update({ ChapterTable.id eq id.toString() }) {
                    it[downloadStatus] = DownloadStatus.PENDING.name
                    it[updatedAt] = Instant.now().toKotlinInstant()
                }
            if (updated == 0) {
                Result.Failure(AppError.NotFound("Chapter", id.toString()))
            } else {
                Result.Success(Unit)
            }
        }

    override suspend fun removeSeries(id: Id): Result<Unit> {
        val result =
            dbQuery {
                val deleted = SeriesTable.deleteWhere { SeriesTable.id eq id.toString() }
                if (deleted == 0) {
                    Result.Failure(AppError.NotFound("Series", id.toString()))
                } else {
                    Result.Success(Unit)
                }
            }
        if (result is Result.Success) {
            fileStorage.deleteSeriesFiles(id.toString())
        }
        return result
    }

    suspend fun updateMetadata(
        id: Id,
        title: String,
        coverUrl: String?,
        description: String?,
    ): Result<Unit> =
        dbQuery {
            val current =
                SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("Series", id.toString()))
            val userEdited = current[SeriesTable.userEditedFields].split(",").filter { it.isNotBlank() }.toSet()
            SeriesTable.update({ SeriesTable.id eq id.toString() }) {
                if ("title" !in userEdited) it[SeriesTable.title] = title
                if ("coverUrl" !in userEdited) it[SeriesTable.coverUrl] = coverUrl
                if ("description" !in userEdited) it[SeriesTable.description] = description
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }
            Result.Success(Unit)
        }

    suspend fun updateEnrichedFields(
        id: Id,
        author: String?,
        artist: String?,
        year: Int?,
        upstreamStatus: UpstreamStatus?,
        genres: List<String>,
    ): Result<Unit> =
        dbQuery {
            val current =
                SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("Series", id.toString()))
            val userEdited = current[SeriesTable.userEditedFields].split(",").filter { it.isNotBlank() }.toSet()
            SeriesTable.update({ SeriesTable.id eq id.toString() }) {
                if ("author" !in userEdited) it[SeriesTable.author] = author
                if ("artist" !in userEdited) it[SeriesTable.artist] = artist
                if ("year" !in userEdited) it[SeriesTable.year] = year
                if ("upstreamStatus" !in userEdited) it[SeriesTable.upstreamStatus] = upstreamStatus?.name
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }
            if ("genres" !in userEdited) {
                SeriesGenresTable.deleteWhere { SeriesGenresTable.seriesId eq id.toString() }
                genres.forEach { genre ->
                    SeriesGenresTable.insert {
                        it[SeriesGenresTable.seriesId] = id.toString()
                        it[SeriesGenresTable.genre] = genre
                    }
                }
            }
            Result.Success(Unit)
        }

    override suspend fun inLibraryExternalIds(
        connectorId: String,
        externalIds: List<String>,
    ): Result<Set<String>> =
        dbQuery {
            if (externalIds.isEmpty()) return@dbQuery Result.Success(emptySet())
            val found =
                SeriesTable
                    .selectAll()
                    .where { (SeriesTable.connectorId eq connectorId) and (SeriesTable.externalId inList externalIds) }
                    .mapNotNull { it[SeriesTable.externalId] }
                    .toSet()
            Result.Success(found)
        }

    override suspend fun updateSeries(
        id: Id,
        autoDownload: Boolean?,
        defaultFormat: ChapterFormat?,
        title: String?,
        coverUrl: String?,
        description: String?,
        author: String?,
        artist: String?,
        year: Int?,
        upstreamStatus: UpstreamStatus?,
        genres: List<String>?,
        clearFields: Set<String>,
    ): Result<Series> =
        dbQuery {
            val current =
                SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.singleOrNull()
                    ?: return@dbQuery Result.Failure(AppError.NotFound("Series", id.toString()))

            val tracked = current[SeriesTable.userEditedFields].split(",").filter { it.isNotBlank() }.toMutableSet()
            clearFields.forEach { tracked.remove(it) }
            if (title != null) tracked.add("title")
            if (coverUrl != null) tracked.add("coverUrl")
            if (description != null) tracked.add("description")
            if (author != null) tracked.add("author")
            if (artist != null) tracked.add("artist")
            if (year != null) tracked.add("year")
            if (upstreamStatus != null) tracked.add("upstreamStatus")
            if (genres != null) tracked.add("genres")

            SeriesTable.update({ SeriesTable.id eq id.toString() }) {
                if (autoDownload != null) it[SeriesTable.autoDownload] = autoDownload
                if (defaultFormat != null) it[SeriesTable.defaultFormat] = defaultFormat.toString()
                if (title != null) it[SeriesTable.title] = title
                if (coverUrl != null) {
                    it[SeriesTable.coverUrl] = coverUrl
                } else if ("coverUrl" in clearFields) {
                    it[SeriesTable.coverUrl] = null
                }
                if (description != null) {
                    it[SeriesTable.description] = description
                } else if ("description" in clearFields) {
                    it[SeriesTable.description] = null
                }
                if (author != null) {
                    it[SeriesTable.author] = author
                } else if ("author" in clearFields) {
                    it[SeriesTable.author] = null
                }
                if (artist != null) {
                    it[SeriesTable.artist] = artist
                } else if ("artist" in clearFields) {
                    it[SeriesTable.artist] = null
                }
                if (year != null) {
                    it[SeriesTable.year] = year
                } else if ("year" in clearFields) {
                    it[SeriesTable.year] = null
                }
                if (upstreamStatus != null) {
                    it[SeriesTable.upstreamStatus] = upstreamStatus.name
                } else if ("upstreamStatus" in clearFields) {
                    it[SeriesTable.upstreamStatus] = null
                }
                it[SeriesTable.userEditedFields] = tracked.joinToString(",")
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }

            if (genres != null) {
                SeriesGenresTable.deleteWhere { SeriesGenresTable.seriesId eq id.toString() }
                genres.forEach { genre ->
                    SeriesGenresTable.insert {
                        it[SeriesGenresTable.seriesId] = id.toString()
                        it[SeriesGenresTable.genre] = genre
                    }
                }
            } else if ("genres" in clearFields) {
                SeriesGenresTable.deleteWhere { SeriesGenresTable.seriesId eq id.toString() }
            }

            val row = SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.single()
            Result.Success(row.toSeries(genresFor(id.toString())))
        }

    suspend fun stampUpdatedAt(id: Id): Unit =
        dbQuery {
            SeriesTable.update({ SeriesTable.id eq id.toString() }) {
                it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
            }
        }

    private fun org.jetbrains.exposed.sql.Transaction.genresFor(seriesId: String): List<String> =
        SeriesGenresTable
            .selectAll()
            .where { SeriesGenresTable.seriesId eq seriesId }
            .map { it[SeriesGenresTable.genre] }

    private fun org.jetbrains.exposed.sql.Transaction.genresForIds(ids: List<String>): Map<String, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return SeriesGenresTable
            .selectAll()
            .where { SeriesGenresTable.seriesId inList ids }
            .groupBy({ it[SeriesGenresTable.seriesId] }, { it[SeriesGenresTable.genre] })
    }

    private fun ResultRow.toSeries(genres: List<String> = emptyList()) =
        Series(
            id = Id.from(this[SeriesTable.id]),
            title = this[SeriesTable.title],
            connectorId = this[SeriesTable.connectorId],
            externalId = this[SeriesTable.externalId],
            language = this[SeriesTable.language],
            status = SeriesStatus.valueOf(this[SeriesTable.status]),
            autoDownload = this[SeriesTable.autoDownload],
            defaultFormat = this[SeriesTable.defaultFormat]?.let { ChapterFormat.fromString(it) },
            coverUrl = this[SeriesTable.coverUrl],
            description = this[SeriesTable.description],
            author = this[SeriesTable.author],
            artist = this[SeriesTable.artist],
            year = this[SeriesTable.year],
            upstreamStatus = this[SeriesTable.upstreamStatus]?.let { runCatching { UpstreamStatus.valueOf(it.uppercase()) }.getOrNull() },
            genres = genres,
            addedAt = this[SeriesTable.addedAt].toJavaInstant(),
            updatedAt = this[SeriesTable.updatedAt].toJavaInstant(),
        )

    // moves storage dirs with no matching DB entry to .trash, removes unfinished downloads and orphaned covers
    // called on startup to cover crash window between DB delete and file delete
    suspend fun cleanupOrphanedFiles() {
        val knownIds =
            dbQuery {
                SeriesTable.selectAll().map { it[SeriesTable.id] }.toSet()
            }
        fileStorage.cleanupOrphanedDirs(knownIds)
        fileStorage.sweepPartialWrites()
        fileStorage.cleanupOrphanedThumbnails(knownIds)
    }

    private fun ResultRow.toChapter() =
        Chapter(
            id = Id.from(this[ChapterTable.id]),
            seriesId = Id.from(this[ChapterTable.seriesId]),
            title = this[ChapterTable.title],
            chapterIndex = this[ChapterTable.chapterIndex],
            externalId = this[ChapterTable.externalId],
            downloadStatus = DownloadStatus.valueOf(this[ChapterTable.downloadStatus]),
            format = this[ChapterTable.format]?.let { ChapterFormat.fromString(it) },
            pageCount = this[ChapterTable.pageCount],
            addedAt = this[ChapterTable.addedAt].toJavaInstant(),
            updatedAt = this[ChapterTable.updatedAt].toJavaInstant(),
        )
}
