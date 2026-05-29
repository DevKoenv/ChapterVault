package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesStatusTable
import dev.koenv.chaptervault.kernel.api.ReadingStatusApi
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class UserSeriesStatusRepository : ReadingStatusApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun setStatus(
        userId: Id,
        seriesId: Id,
        status: ReadingStatus,
    ): Result<Unit> =
        dbQuery {
            UserSeriesStatusTable.upsert {
                it[UserSeriesStatusTable.userId] = userId.toString()
                it[UserSeriesStatusTable.seriesId] = seriesId.toString()
                it[UserSeriesStatusTable.status] = status.name
                it[UserSeriesStatusTable.updatedAt] = Instant.now().toString()
            }
            Result.Success(Unit)
        }

    override suspend fun clearStatus(
        userId: Id,
        seriesId: Id,
    ): Result<Unit> =
        dbQuery {
            UserSeriesStatusTable.deleteWhere {
                (UserSeriesStatusTable.userId eq userId.toString()) and
                    (UserSeriesStatusTable.seriesId eq seriesId.toString())
            }
            Result.Success(Unit)
        }

    override suspend fun getStatus(
        userId: Id,
        seriesId: Id,
    ): ReadingStatus? =
        dbQuery {
            UserSeriesStatusTable
                .selectAll()
                .where {
                    (UserSeriesStatusTable.userId eq userId.toString()) and
                        (UserSeriesStatusTable.seriesId eq seriesId.toString())
                }.firstOrNull()
                ?.let { row ->
                    runCatching { ReadingStatus.valueOf(row[UserSeriesStatusTable.status]) }.getOrNull()
                }
        }
}
