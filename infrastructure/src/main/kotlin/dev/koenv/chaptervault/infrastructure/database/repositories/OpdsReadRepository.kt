package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesGenresTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.kernel.api.ChapterWithSeries
import dev.koenv.chaptervault.kernel.api.GenreCount
import dev.koenv.chaptervault.kernel.api.LanguageCount
import dev.koenv.chaptervault.kernel.api.OpdsReadApi
import dev.koenv.chaptervault.kernel.api.OpdsSeriesFilter
import dev.koenv.chaptervault.kernel.api.RecentlyReadEntry
import dev.koenv.chaptervault.kernel.api.StatusCount
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.library.UpstreamStatus
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private val logger = KotlinLogging.logger {}

class OpdsReadRepository : OpdsReadApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun listReadHistory(userId: Id, request: PageRequest): Result<Pagination<RecentlyReadEntry>> =
        dbQuery {
            val maxReadAt = ProgressTable.readAt.max()
            val chaptersReadCount = ProgressTable.chapterId.count()

            // Single aggregate query — no N+1 for either chaptersRead or totalChapters
            val allAggRows = ProgressTable
                .join(ChapterTable, JoinType.INNER, ProgressTable.chapterId, ChapterTable.id)
                .select(ChapterTable.seriesId, maxReadAt, chaptersReadCount)
                .where { ProgressTable.userId eq userId.toString() }
                .groupBy(ChapterTable.seriesId)
                .orderBy(maxReadAt, SortOrder.DESC)
                .toList()

            val total = allAggRows.size.toLong()
            val pageRows = allAggRows.drop(request.page * request.size).take(request.size)
            val pageSeriesIds = pageRows.map { it[ChapterTable.seriesId] }

            if (pageSeriesIds.isEmpty()) {
                return@dbQuery Result.Success(Pagination(emptyList(), request.page, request.size, total))
            }

            val seriesMap = SeriesTable
                .selectAll()
                .where { SeriesTable.id inList pageSeriesIds }
                .associate { it[SeriesTable.id] to it }
            val genreMap = genresForIds(pageSeriesIds)

            val totalChaptersMap: Map<String, Long> = run {
                val countExpr = ChapterTable.id.count()
                ChapterTable
                    .select(ChapterTable.seriesId, countExpr)
                    .where { ChapterTable.seriesId inList pageSeriesIds }
                    .groupBy(ChapterTable.seriesId)
                    .associate { it[ChapterTable.seriesId] to it[countExpr] }
            }

            val items = pageRows.mapNotNull { row ->
                val seriesId = row[ChapterTable.seriesId]
                val seriesRow = seriesMap[seriesId] ?: return@mapNotNull null
                RecentlyReadEntry(
                    series = seriesRow.toSeries(genreMap[seriesId].orEmpty()),
                    lastReadAt = row[maxReadAt]?.toJavaInstant() ?: return@mapNotNull null,
                    chaptersRead = row[chaptersReadCount].toInt(),
                    totalChapters = totalChaptersMap[seriesId]?.toInt() ?: 0,
                )
            }

            Result.Success(Pagination(items, request.page, request.size, total))
        }

    override suspend fun listRecentlyAddedChapters(request: PageRequest): Result<Pagination<ChapterWithSeries>> =
        dbQuery {
            val total = ChapterTable.selectAll().count()
            val rows = ChapterTable
                .join(SeriesTable, JoinType.INNER, ChapterTable.seriesId, SeriesTable.id)
                .selectAll()
                .orderBy(ChapterTable.addedAt, SortOrder.DESC)
                .limit(request.size, (request.page * request.size).toLong())
                .toList()

            val seriesIds = rows.map { it[ChapterTable.seriesId] }.distinct()
            val genreMap = genresForIds(seriesIds)

            val items = rows.map { row ->
                ChapterWithSeries(
                    chapter = row.toChapter(),
                    series = row.toSeries(genreMap[row[ChapterTable.seriesId]].orEmpty()),
                )
            }

            Result.Success(Pagination(items, request.page, request.size, total))
        }

    override suspend fun listGenres(): Result<List<GenreCount>> =
        dbQuery {
            val countExpr = SeriesGenresTable.seriesId.count()
            val genres = SeriesGenresTable
                .select(SeriesGenresTable.genre, countExpr)
                .groupBy(SeriesGenresTable.genre)
                .orderBy(countExpr, SortOrder.DESC)
                .map { GenreCount(it[SeriesGenresTable.genre], it[countExpr].toInt()) }
            Result.Success(genres)
        }

    override suspend fun listLanguages(): Result<List<LanguageCount>> =
        dbQuery {
            val countExpr = SeriesTable.id.count()
            val languages = SeriesTable
                .select(SeriesTable.language, countExpr)
                .groupBy(SeriesTable.language)
                .orderBy(countExpr, SortOrder.DESC)
                .map { LanguageCount(it[SeriesTable.language], it[countExpr].toInt()) }
            Result.Success(languages)
        }

    override suspend fun listUpstreamStatuses(): Result<List<StatusCount>> =
        dbQuery {
            val countExpr = SeriesTable.id.count()
            val knownStatuses = UpstreamStatus.entries.map { it.name }
            val statuses = SeriesTable
                .select(SeriesTable.upstreamStatus, countExpr)
                .where { SeriesTable.upstreamStatus.isNotNull() }
                .groupBy(SeriesTable.upstreamStatus)
                .orderBy(countExpr, SortOrder.DESC)
                .mapNotNull { row ->
                    val raw = row[SeriesTable.upstreamStatus] ?: return@mapNotNull null
                    if (raw.uppercase() !in knownStatuses) {
                        logger.warn { "Unknown upstream_status '$raw' found in database" }
                    }
                    StatusCount(raw, row[countExpr].toInt())
                }
            Result.Success(statuses)
        }

    override suspend fun listSeriesFiltered(filter: OpdsSeriesFilter, request: PageRequest): Result<Pagination<Series>> =
        dbQuery {
            var condition: Op<Boolean> = Op.TRUE

            filter.genre?.let { genre ->
                val ids = SeriesGenresTable
                    .select(SeriesGenresTable.seriesId)
                    .where { SeriesGenresTable.genre eq genre }
                    .map { it[SeriesGenresTable.seriesId] }
                condition = condition and (SeriesTable.id inList ids)
            }
            filter.language?.let { condition = condition and (SeriesTable.language eq it) }
            filter.upstreamStatus?.let { condition = condition and (SeriesTable.upstreamStatus eq it) }

            val total = SeriesTable.selectAll().where { condition }.count()
            val rows = SeriesTable
                .selectAll()
                .where { condition }
                .orderBy(SeriesTable.title, SortOrder.ASC)
                .limit(request.size, (request.page * request.size).toLong())
                .toList()

            val genreMap = genresForIds(rows.map { it[SeriesTable.id] })
            val items = rows.map { it.toSeries(genreMap[it[SeriesTable.id]].orEmpty()) }

            Result.Success(Pagination(items, request.page, request.size, total))
        }

    private fun Transaction.genresForIds(ids: List<String>): Map<String, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return SeriesGenresTable
            .selectAll()
            .where { SeriesGenresTable.seriesId inList ids }
            .groupBy({ it[SeriesGenresTable.seriesId] }, { it[SeriesGenresTable.genre] })
    }

    private fun ResultRow.toSeries(genres: List<String> = emptyList()): Series {
        val rawStatus = this[SeriesTable.upstreamStatus]
        val upstreamStatus = rawStatus?.let {
            runCatching { UpstreamStatus.valueOf(it.uppercase()) }
                .onFailure { logger.warn { "Unknown upstream_status '$rawStatus' for series ${this[SeriesTable.id]}" } }
                .getOrNull()
        }
        return Series(
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
            upstreamStatus = upstreamStatus,
            genres = genres,
            addedAt = this[SeriesTable.addedAt].toJavaInstant(),
            updatedAt = this[SeriesTable.updatedAt].toJavaInstant(),
        )
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
