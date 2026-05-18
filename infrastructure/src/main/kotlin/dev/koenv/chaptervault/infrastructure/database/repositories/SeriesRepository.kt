package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class SeriesRepository : LibraryReadApi, LibraryCommandApi {

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun getSeries(id: Id): Result<Series> = dbQuery {
        SeriesTable.selectAll()
            .where { SeriesTable.id eq id.toString() }
            .singleOrNull()
            ?.toSeries()
            ?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("Series", id.toString()))
    }

    override suspend fun listSeries(request: PageRequest): Result<Pagination<Series>> = dbQuery {
        val total = SeriesTable.selectAll().count()
        val items = SeriesTable.selectAll()
            .limit(request.size, (request.page * request.size).toLong())
            .map { it.toSeries() }
        Result.Success(Pagination(items, request.page, request.size, total))
    }

    override suspend fun searchLibrary(query: String, request: PageRequest): Result<Pagination<Series>> = dbQuery {
        val total = SeriesTable.selectAll().where { SeriesTable.title like "%$query%" }.count()
        val items = SeriesTable.selectAll()
            .where { SeriesTable.title like "%$query%" }
            .limit(request.size, (request.page * request.size).toLong())
            .map { it.toSeries() }
        Result.Success(Pagination(items, request.page, request.size, total))
    }

    override suspend fun getChapter(id: Id): Result<Chapter> = dbQuery {
        ChapterTable.selectAll()
            .where { ChapterTable.id eq id.toString() }
            .singleOrNull()
            ?.toChapter()
            ?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("Chapter", id.toString()))
    }

    override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> = dbQuery {
        val chapters = ChapterTable.selectAll()
            .where { ChapterTable.seriesId eq seriesId.toString() }
            .map { it.toChapter() }
        Result.Success(chapters)
    }

    override suspend fun addToLibrary(connectorId: String, externalId: String, language: String, autoDownload: Boolean): Result<Series> = dbQuery {
        val existing = SeriesTable.selectAll()
            .where { (SeriesTable.connectorId eq connectorId) and (SeriesTable.externalId eq externalId) and (SeriesTable.language eq language) }
            .singleOrNull()

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

        Result.Success(SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.single().toSeries())
    }

    override suspend fun removeSeries(id: Id): Result<Unit> = dbQuery {
        val deleted = SeriesTable.deleteWhere { SeriesTable.id eq id.toString() }
        if (deleted == 0) Result.Failure(AppError.NotFound("Series", id.toString()))
        else Result.Success(Unit)
    }

    suspend fun updateMetadata(id: Id, title: String, coverUrl: String?, description: String?): Result<Unit> = dbQuery {
        val updated = SeriesTable.update({ SeriesTable.id eq id.toString() }) {
            it[SeriesTable.title] = title
            it[SeriesTable.coverUrl] = coverUrl
            it[SeriesTable.description] = description
            it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
        }
        if (updated == 0) Result.Failure(AppError.NotFound("Series", id.toString()))
        else Result.Success(Unit)
    }

    override suspend fun updateSeries(id: Id, autoDownload: Boolean?, defaultFormat: ChapterFormat?): Result<Series> = dbQuery {
        val count = SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.count()
        if (count == 0L) return@dbQuery Result.Failure(AppError.NotFound("Series", id.toString()))

        SeriesTable.update({ SeriesTable.id eq id.toString() }) {
            if (autoDownload != null) it[SeriesTable.autoDownload] = autoDownload
            if (defaultFormat != null) it[SeriesTable.defaultFormat] = defaultFormat.toString()
            it[SeriesTable.updatedAt] = Instant.now().toKotlinInstant()
        }

        Result.Success(SeriesTable.selectAll().where { SeriesTable.id eq id.toString() }.single().toSeries())
    }

    private fun ResultRow.toSeries() = Series(
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
        addedAt = this[SeriesTable.addedAt].toJavaInstant(),
        updatedAt = this[SeriesTable.updatedAt].toJavaInstant(),
    )

    private fun ResultRow.toChapter() = Chapter(
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
