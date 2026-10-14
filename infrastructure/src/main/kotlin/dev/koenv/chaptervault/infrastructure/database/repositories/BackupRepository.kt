package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesStatusTable
import dev.koenv.chaptervault.kernel.api.BackupApi
import dev.koenv.chaptervault.kernel.api.BackupImportSummary
import dev.koenv.chaptervault.kernel.api.BackupProgress
import dev.koenv.chaptervault.kernel.api.BackupReadingStatus
import dev.koenv.chaptervault.kernel.api.LibraryBackup
import dev.koenv.chaptervault.kernel.library.ReadingStatus
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
                        val connectorId = row[SeriesTable.connectorId] ?: return@mapNotNull null
                        val externalId = row[SeriesTable.externalId] ?: return@mapNotNull null
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
                            connectorId = connectorId,
                            externalId = externalId,
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
                    .mapNotNull { row ->
                        val connectorId = row[SeriesTable.connectorId] ?: return@mapNotNull null
                        val externalId = row[SeriesTable.externalId] ?: return@mapNotNull null
                        BackupReadingStatus(
                            connectorId = connectorId,
                            externalId = externalId,
                            status = row[UserSeriesStatusTable.status],
                        )
                    }

            Result.Success(
                LibraryBackup(
                    exportedAt = exportedAt,
                    progress = progress,
                    readingStatuses = readingStatuses,
                ),
            )
        }

    override suspend fun importBackup(
        userId: Id,
        backup: LibraryBackup,
    ): Result<BackupImportSummary> =
        dbQuery {
            val uid = userId.toString()
            val progressRestored = backup.progress.count { restoreProgress(uid, it) }
            val statusesRestored = backup.readingStatuses.count { restoreStatus(uid, it) }

            Result.Success(
                BackupImportSummary(
                    progressRestored = progressRestored,
                    progressSkipped = backup.progress.size - progressRestored,
                    statusesRestored = statusesRestored,
                    statusesSkipped = backup.readingStatuses.size - statusesRestored,
                ),
            )
        }

    /** Stores one progress entry; false when it was skipped (bad page, or the series is not in this library). */
    private fun Transaction.restoreProgress(
        uid: String,
        p: BackupProgress,
    ): Boolean {
        val series = if (p.latestPage < 0) null else findSeries(p.connectorId, p.externalId)
        if (series == null) return false
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
        return true
    }

    /** Stores one reading status; false when it was skipped (unknown status, or the series is not in this library). */
    private fun Transaction.restoreStatus(
        uid: String,
        s: BackupReadingStatus,
    ): Boolean {
        // a status the server cannot read back would break the user's library listing
        val known = ReadingStatus.entries.any { it.name == s.status }
        val series = if (known) findSeries(s.connectorId, s.externalId) else null
        if (series == null) return false
        UserSeriesStatusTable.upsert {
            it[UserSeriesStatusTable.userId] = uid
            it[UserSeriesStatusTable.seriesId] = series[SeriesTable.id]
            it[UserSeriesStatusTable.status] = s.status
            it[UserSeriesStatusTable.updatedAt] = Instant.now().toKotlinInstant()
        }
        return true
    }

    private fun Transaction.findSeries(
        connectorId: String,
        externalId: String,
    ) = SeriesTable
        .selectAll()
        .where { (SeriesTable.connectorId eq connectorId) and (SeriesTable.externalId eq externalId) }
        .singleOrNull()
}
