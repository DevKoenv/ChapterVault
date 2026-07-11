package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesProgressTable
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadProgress
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class ProgressRepository : ProgressApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun markRead(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> =
        dbQuery {
            val chapter =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId.toString() }
                    .singleOrNull() ?: return@dbQuery Result.Success(Unit)

            val seriesId = chapter[ChapterTable.seriesId]
            val newIndex = chapter[ChapterTable.chapterIndex]

            val current =
                UserSeriesProgressTable
                    .selectAll()
                    .where {
                        (UserSeriesProgressTable.userId eq userId.toString()) and
                            (UserSeriesProgressTable.seriesId eq seriesId)
                    }.singleOrNull()

            val shouldUpdate =
                if (current == null) {
                    true
                } else {
                    val currentChapterId = current[UserSeriesProgressTable.latestChapterId]
                    if (currentChapterId == null) {
                        true
                    } else {
                        val currentIndex =
                            ChapterTable
                                .selectAll()
                                .where { ChapterTable.id eq currentChapterId }
                                .singleOrNull()
                                ?.get(ChapterTable.chapterIndex)
                                ?: -1.0
                        newIndex >= currentIndex
                    }
                }

            if (shouldUpdate) {
                UserSeriesProgressTable.upsert {
                    it[UserSeriesProgressTable.userId] = userId.toString()
                    it[UserSeriesProgressTable.seriesId] = seriesId
                    it[UserSeriesProgressTable.latestChapterId] = chapterId.toString()
                    it[UserSeriesProgressTable.latestPage] = 0
                    it[UserSeriesProgressTable.readAt] = Instant.now().toKotlinInstant()
                }
            }
            Result.Success(Unit)
        }

    override suspend fun markUnread(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> =
        dbQuery {
            val chapter =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId.toString() }
                    .singleOrNull() ?: return@dbQuery Result.Success(Unit)

            val seriesId = chapter[ChapterTable.seriesId]

            val current =
                UserSeriesProgressTable
                    .selectAll()
                    .where {
                        (UserSeriesProgressTable.userId eq userId.toString()) and
                            (UserSeriesProgressTable.seriesId eq seriesId)
                    }.singleOrNull()

            if (current != null && current[UserSeriesProgressTable.latestChapterId] == chapterId.toString()) {
                UserSeriesProgressTable.deleteWhere {
                    (UserSeriesProgressTable.userId eq userId.toString()) and
                        (UserSeriesProgressTable.seriesId eq seriesId)
                }
            }
            Result.Success(Unit)
        }

    override suspend fun getProgress(
        userId: Id,
        seriesId: Id,
    ): Result<ReadProgress> =
        dbQuery {
            val totalCount =
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.seriesId eq seriesId.toString() }
                    .count()
                    .toInt()

            val progressRow =
                UserSeriesProgressTable
                    .selectAll()
                    .where {
                        (UserSeriesProgressTable.userId eq userId.toString()) and
                            (UserSeriesProgressTable.seriesId eq seriesId.toString())
                    }.singleOrNull()

            if (progressRow == null) {
                return@dbQuery Result.Success(
                    ReadProgress(
                        seriesId = seriesId,
                        latestChapterId = null,
                        latestPage = 0,
                        unreadCount = totalCount,
                        totalCount = totalCount,
                    ),
                )
            }

            val latestChapterId = progressRow[UserSeriesProgressTable.latestChapterId]
            val latestPage = progressRow[UserSeriesProgressTable.latestPage]

            val unreadCount =
                if (latestChapterId == null) {
                    totalCount
                } else {
                    val latestIndex =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id eq latestChapterId }
                            .singleOrNull()
                            ?.get(ChapterTable.chapterIndex)
                            ?: 0.0
                    val readCount =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.seriesId eq seriesId.toString() }
                            .count { it[ChapterTable.chapterIndex] <= latestIndex }
                    (totalCount - readCount).coerceAtLeast(0)
                }

            Result.Success(
                ReadProgress(
                    seriesId = seriesId,
                    latestChapterId = latestChapterId?.let { Id.from(it) },
                    latestPage = latestPage,
                    unreadCount = unreadCount,
                    totalCount = totalCount,
                ),
            )
        }
}
