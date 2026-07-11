package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesOverridesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesStatusTable
import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.BackupBookmark
import dev.koenv.chaptervault.kernel.api.BackupImportSummary
import dev.koenv.chaptervault.kernel.api.BackupOverride
import dev.koenv.chaptervault.kernel.api.BackupProgress
import dev.koenv.chaptervault.kernel.api.BackupReadingStatus
import dev.koenv.chaptervault.kernel.api.LibraryBackup
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class BackupRepository : BackupApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun exportBackup(
        userId: Id,
        exportedAt: String,
    ): Result<LibraryBackup> =
        dbQuery {
            val uid = userId.toString()

            val progress =
                UserSeriesProgressTable
                    .join(SeriesTable, JoinType.INNER, UserSeriesProgressTable.seriesId, SeriesTable.id)
                    .selectAll()
                    .where { UserSeriesProgressTable.userId eq uid }
                    .mapNotNull { row ->
                        val latestChapterId = row[UserSeriesProgressTable.latestChapterId]
                        val latestChapterExternalId =
                            latestChapterId?.let { cid ->
                                ChapterTable
                                    .selectAll()
                                    .where { ChapterTable.id eq cid }
                                    .singleOrNull()
                                    ?.get(ChapterTable.externalId)
                            }
                        BackupProgress(
                            connectorId = row[SeriesTable.connectorId],
                            externalId = row[SeriesTable.externalId],
                            latestChapterExternalId = latestChapterExternalId,
                            latestPage = row[UserSeriesProgressTable.latestPage],
                            readAt = row[UserSeriesProgressTable.readAt].toJavaInstant().toString(),
                        )
                    }

            val readingStatuses =
                UserSeriesStatusTable
                    .join(SeriesTable, JoinType.INNER, UserSeriesStatusTable.seriesId, SeriesTable.id)
                    .selectAll()
                    .where { UserSeriesStatusTable.userId eq uid }
                    .map { row ->
                        BackupReadingStatus(
                            connectorId = row[SeriesTable.connectorId],
                            externalId = row[SeriesTable.externalId],
                            status = row[UserSeriesStatusTable.status],
                        )
                    }

            val overrides =
                SeriesOverridesTable
                    .join(SeriesTable, JoinType.INNER, SeriesOverridesTable.seriesId, SeriesTable.id)
                    .selectAll()
                    .map { row ->
                        BackupOverride(
                            connectorId = row[SeriesTable.connectorId],
                            externalId = row[SeriesTable.externalId],
                            field = row[SeriesOverridesTable.field],
                            value = row[SeriesOverridesTable.value],
                        )
                    }

            val bookmarks =
                BookmarkTable
                    .join(ChapterTable, JoinType.INNER, BookmarkTable.chapterId, ChapterTable.id)
                    .join(SeriesTable, JoinType.INNER, ChapterTable.seriesId, SeriesTable.id)
                    .selectAll()
                    .where { BookmarkTable.userId eq uid }
                    .map { row ->
                        BackupBookmark(
                            chapterSeriesConnectorId = row[SeriesTable.connectorId],
                            chapterSeriesExternalId = row[SeriesTable.externalId],
                            chapterExternalId = row[ChapterTable.externalId],
                            page = row[BookmarkTable.page],
                            createdAt = row[BookmarkTable.createdAt].toJavaInstant().toString(),
                        )
                    }

            Result.Success(
                LibraryBackup(
                    exportedAt = exportedAt,
                    progress = progress,
                    readingStatuses = readingStatuses,
                    overrides = overrides,
                    bookmarks = bookmarks,
                ),
            )
        }

    override suspend fun importBackup(
        userId: Id,
        backup: LibraryBackup,
    ): Result<BackupImportSummary> =
        dbQuery {
            val uid = userId.toString()
            var progressRestored = 0
            var progressSkipped = 0
            var statusesRestored = 0
            var statusesSkipped = 0
            var overridesRestored = 0
            var overridesSkipped = 0
            var bookmarksRestored = 0
            var bookmarksSkipped = 0

            for (p in backup.progress) {
                val series =
                    SeriesTable
                        .selectAll()
                        .where { (SeriesTable.connectorId eq p.connectorId) and (SeriesTable.externalId eq p.externalId) }
                        .singleOrNull()
                if (series == null) {
                    progressSkipped++
                    continue
                }
                val seriesId = series[SeriesTable.id]
                val latestChapterId =
                    p.latestChapterExternalId?.let { externalId ->
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.seriesId eq seriesId) and (ChapterTable.externalId eq externalId) }
                            .singleOrNull()
                            ?.get(ChapterTable.id)
                    }
                UserSeriesProgressTable.upsert {
                    it[UserSeriesProgressTable.userId] = uid
                    it[UserSeriesProgressTable.seriesId] = seriesId
                    it[UserSeriesProgressTable.latestChapterId] = latestChapterId
                    it[UserSeriesProgressTable.latestPage] = p.latestPage
                    it[UserSeriesProgressTable.readAt] =
                        runCatching {
                            java.time.Instant
                                .parse(p.readAt)
                                .toKotlinInstant()
                        }.getOrElse { Instant.now().toKotlinInstant() }
                }
                progressRestored++
            }

            for (s in backup.readingStatuses) {
                val series =
                    SeriesTable
                        .selectAll()
                        .where { (SeriesTable.connectorId eq s.connectorId) and (SeriesTable.externalId eq s.externalId) }
                        .singleOrNull()
                if (series == null) {
                    statusesSkipped++
                    continue
                }
                UserSeriesStatusTable.upsert {
                    it[UserSeriesStatusTable.userId] = uid
                    it[UserSeriesStatusTable.seriesId] = series[SeriesTable.id]
                    it[UserSeriesStatusTable.status] = s.status
                    it[UserSeriesStatusTable.updatedAt] = Instant.now().toKotlinInstant()
                }
                statusesRestored++
            }

            for (o in backup.overrides) {
                val series =
                    SeriesTable
                        .selectAll()
                        .where { (SeriesTable.connectorId eq o.connectorId) and (SeriesTable.externalId eq o.externalId) }
                        .singleOrNull()
                if (series == null) {
                    overridesSkipped++
                    continue
                }
                SeriesOverridesTable.upsert {
                    it[SeriesOverridesTable.seriesId] = series[SeriesTable.id]
                    it[SeriesOverridesTable.field] = o.field
                    it[SeriesOverridesTable.value] = o.value
                }
                overridesRestored++
            }

            for (b in backup.bookmarks) {
                val series =
                    SeriesTable
                        .selectAll()
                        .where {
                            (SeriesTable.connectorId eq b.chapterSeriesConnectorId) and
                                (SeriesTable.externalId eq b.chapterSeriesExternalId)
                        }.singleOrNull()
                if (series == null) {
                    bookmarksSkipped++
                    continue
                }
                val chapter =
                    ChapterTable
                        .selectAll()
                        .where { (ChapterTable.seriesId eq series[SeriesTable.id]) and (ChapterTable.externalId eq b.chapterExternalId) }
                        .singleOrNull()
                if (chapter == null) {
                    bookmarksSkipped++
                    continue
                }
                BookmarkTable.upsert(BookmarkTable.id) {
                    it[BookmarkTable.id] = Id.generate().toString()
                    it[BookmarkTable.userId] = uid
                    it[BookmarkTable.chapterId] = chapter[ChapterTable.id]
                    it[BookmarkTable.page] = b.page
                    it[BookmarkTable.createdAt] =
                        runCatching {
                            java.time.Instant
                                .parse(b.createdAt)
                                .toKotlinInstant()
                        }.getOrElse { Instant.now().toKotlinInstant() }
                }
                bookmarksRestored++
            }

            Result.Success(
                BackupImportSummary(
                    progressRestored = progressRestored,
                    progressSkipped = progressSkipped,
                    statusesRestored = statusesRestored,
                    statusesSkipped = statusesSkipped,
                    overridesRestored = overridesRestored,
                    overridesSkipped = overridesSkipped,
                    bookmarksRestored = bookmarksRestored,
                    bookmarksSkipped = bookmarksSkipped,
                ),
            )
        }
}
