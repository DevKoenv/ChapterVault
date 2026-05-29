package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class ChapterRepository {

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun insertChapter(
        seriesId: Id,
        title: String,
        chapterIndex: Double,
        externalId: String,
    ): Result<Chapter> = dbQuery {
        val seriesCount = SeriesTable
            .selectAll()
            .where { SeriesTable.id eq seriesId.toString() }
            .count()
        if (seriesCount == 0L) return@dbQuery Result.Failure(AppError.NotFound("Series", seriesId.toString()))

        val existing = ChapterTable.selectAll()
            .where { (ChapterTable.seriesId eq seriesId.toString()) and (ChapterTable.externalId eq externalId) }
            .singleOrNull()
        if (existing != null) return@dbQuery Result.Failure(AppError.Conflict("Chapter '$externalId' already exists"))

        val id = Id.generate()
        val now = Instant.now().toKotlinInstant()
        ChapterTable.insert {
            it[ChapterTable.id] = id.toString()
            it[ChapterTable.seriesId] = seriesId.toString()
            it[ChapterTable.title] = title
            it[ChapterTable.chapterIndex] = chapterIndex
            it[ChapterTable.externalId] = externalId
            it[ChapterTable.downloadStatus] = DownloadStatus.AVAILABLE.name
            it[ChapterTable.format] = null
            it[ChapterTable.pageCount] = null
            it[ChapterTable.addedAt] = now
            it[ChapterTable.updatedAt] = now
        }
        val chapter = ChapterTable.selectAll()
            .where { ChapterTable.id eq id.toString() }
            .single()
            .toChapter()
        Result.Success(chapter)
    }

    suspend fun updateDownloadStatus(
        id: Id,
        status: DownloadStatus,
        pageCount: Int? = null,
    ): Result<Chapter> = dbQuery {
        val count = ChapterTable.selectAll()
            .where { ChapterTable.id eq id.toString() }
            .count()
        if (count == 0L) return@dbQuery Result.Failure(AppError.NotFound("Chapter", id.toString()))

        ChapterTable.update({ ChapterTable.id eq id.toString() }) {
            it[ChapterTable.downloadStatus] = status.name
            if (pageCount != null) it[ChapterTable.pageCount] = pageCount
            it[ChapterTable.updatedAt] = Instant.now().toKotlinInstant()
        }

        val chapter = ChapterTable.selectAll()
            .where { ChapterTable.id eq id.toString() }
            .single()
            .toChapter()
        Result.Success(chapter)
    }

    suspend fun getChapter(id: Id): Result<Chapter> = dbQuery {
        ChapterTable.selectAll()
            .where { ChapterTable.id eq id.toString() }
            .singleOrNull()
            ?.toChapter()
            ?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("Chapter", id.toString()))
    }

    suspend fun listChapters(seriesId: Id): Result<List<Chapter>> = dbQuery {
        val chapters = ChapterTable.selectAll()
            .where { ChapterTable.seriesId eq seriesId.toString() }
            .orderBy(ChapterTable.chapterIndex, SortOrder.ASC)
            .map { it.toChapter() }
        Result.Success(chapters)
    }

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
