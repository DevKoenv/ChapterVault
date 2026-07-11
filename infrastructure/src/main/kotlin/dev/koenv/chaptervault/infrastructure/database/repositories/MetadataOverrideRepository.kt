package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.SeriesOverridesTable
import dev.koenv.chaptervault.kernel.api.MetadataOverrideApi
import dev.koenv.chaptervault.shared.result.AppError
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

val OVERRIDABLE_FIELDS = setOf("title", "description", "author", "artist", "coverUrl", "status", "upstreamStatus")

class MetadataOverrideRepository : MetadataOverrideApi {
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun setOverride(
        seriesId: Id,
        field: String,
        value: String,
    ): Result<Unit> {
        if (field !in OVERRIDABLE_FIELDS) {
            return Result.Failure(
                AppError.ValidationError("Unknown field '$field'. Allowed: ${OVERRIDABLE_FIELDS.sorted().joinToString()}"),
            )
        }
        return dbQuery {
            SeriesOverridesTable.upsert {
                it[SeriesOverridesTable.seriesId] = seriesId.toString()
                it[SeriesOverridesTable.field] = field
                it[SeriesOverridesTable.value] = value
            }
            Result.Success(Unit)
        }
    }

    override suspend fun clearOverride(
        seriesId: Id,
        field: String,
    ): Result<Unit> =
        dbQuery {
            SeriesOverridesTable.deleteWhere {
                (SeriesOverridesTable.seriesId eq seriesId.toString()) and
                    (SeriesOverridesTable.field eq field)
            }
            Result.Success(Unit)
        }

    override suspend fun getOverrides(seriesId: Id): Result<Map<String, String>> =
        dbQuery {
            val overrides =
                SeriesOverridesTable
                    .selectAll()
                    .where { SeriesOverridesTable.seriesId eq seriesId.toString() }
                    .associate { it[SeriesOverridesTable.field] to it[SeriesOverridesTable.value] }
            Result.Success(overrides)
        }

    fun overridesForIds(seriesIds: List<String>): Map<String, Map<String, String>> {
        if (seriesIds.isEmpty()) return emptyMap()
        return SeriesOverridesTable
            .selectAll()
            .where { SeriesOverridesTable.seriesId inList seriesIds }
            .groupBy { it[SeriesOverridesTable.seriesId] }
            .mapValues { (_, rows) -> rows.associate { it[SeriesOverridesTable.field] to it[SeriesOverridesTable.value] } }
    }
}
