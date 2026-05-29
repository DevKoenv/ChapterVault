package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.infrastructure.database.entities.ProgressTable
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.ReadProgress
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class ProgressRepository : ProgressApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun markRead(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> =
        dbQuery {
            val u = userId.toString()
            val c = chapterId.toString()
            val exists =
                ProgressTable
                    .selectAll()
                    .where { (ProgressTable.userId eq u) and (ProgressTable.chapterId eq c) }
                    .count() > 0
            if (!exists) {
                ProgressTable.insert {
                    it[ProgressTable.userId] = u
                    it[ProgressTable.chapterId] = c
                    it[ProgressTable.readAt] = Instant.now().toKotlinInstant()
                }
            }
            Result.Success(Unit)
        }

    override suspend fun markUnread(
        userId: Id,
        chapterId: Id,
    ): Result<Unit> =
        dbQuery {
            ProgressTable.deleteWhere {
                (ProgressTable.userId eq userId.toString()) and (ProgressTable.chapterId eq chapterId.toString())
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

            val readCount =
                ProgressTable
                    .join(ChapterTable, JoinType.INNER, ProgressTable.chapterId, ChapterTable.id)
                    .selectAll()
                    .where {
                        (ProgressTable.userId eq userId.toString()) and
                            (ChapterTable.seriesId eq seriesId.toString())
                    }.count()
                    .toInt()

            Result.Success(ReadProgress(seriesId = seriesId, readCount = readCount, totalCount = totalCount))
        }
}
