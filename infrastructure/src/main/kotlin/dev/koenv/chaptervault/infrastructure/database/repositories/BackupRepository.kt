package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesOverridesTable
import dev.koenv.chaptervault.infrastructure.database.entities.SeriesTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesStatusTable
import dev.koenv.chaptervault.kernel.api.BackupApi
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

            val overrides =
                SeriesOverridesTable
                    .join(SeriesTable, JoinType.INNER, SeriesOverridesTable.seriesId, SeriesTable.id)
                    .selectAll()
                    .mapNotNull { row ->
                        val connectorId = row[SeriesTable.connectorId] ?: return@mapNotNull null
                        val externalId = row[SeriesTable.externalId] ?: return@mapNotNull null
                        BackupOverride(
                            connectorId = connectorId,
                            externalId = externalId,
                            field = row[SeriesOverridesTable.field],
                            value = row[SeriesOverridesTable.value],
                        )
                    }

            Result.Success(
                LibraryBackup(
                    exportedAt = exportedAt,
                    progress = progress,
                    readingStatuses = readingStatuses,
                    overrides = overrides,
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

            Result.Success(
                BackupImportSummary(
                    progressRestored = progressRestored,
                    progressSkipped = progressSkipped,
                    statusesRestored = statusesRestored,
                    statusesSkipped = statusesSkipped,
                    overridesRestored = overridesRestored,
                    overridesSkipped = overridesSkipped,
                ),
            )
        }
}
