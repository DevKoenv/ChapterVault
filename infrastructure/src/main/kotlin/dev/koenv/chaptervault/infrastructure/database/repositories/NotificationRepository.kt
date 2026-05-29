package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.NotificationTargetTable
import dev.koenv.chaptervault.kernel.api.NotificationApi
import dev.koenv.chaptervault.kernel.api.NotificationTarget
import dev.koenv.chaptervault.kernel.api.NotificationTargetInput
import dev.koenv.chaptervault.kernel.api.NotificationTargetPatch
import dev.koenv.chaptervault.kernel.api.NotificationType
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class NotificationRepository : NotificationApi {

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun listTargets(): List<NotificationTarget> = dbQuery {
        NotificationTargetTable.selectAll()
            .orderBy(NotificationTargetTable.createdAt)
            .map { it.toTarget() }
    }

    override suspend fun findTarget(id: Id): Result<NotificationTarget> = dbQuery {
        NotificationTargetTable.selectAll()
            .where { NotificationTargetTable.id eq id.toString() }
            .firstOrNull()
            ?.toTarget()
            ?.let { Result.Success(it) }
            ?: Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))
    }

    override suspend fun createTarget(input: NotificationTargetInput): Result<NotificationTarget> = dbQuery {
        val newId = Id.generate()
        val now = Instant.now()
        NotificationTargetTable.insert {
            it[id] = newId.toString()
            it[name] = input.name
            it[type] = input.type.name
            it[url] = input.url
            it[token] = input.token
            it[enabled] = input.enabled
            it[createdAt] = now.toString()
        }
        Result.Success(
            NotificationTarget(
                id = newId,
                name = input.name,
                type = input.type,
                url = input.url,
                token = input.token,
                enabled = input.enabled,
                createdAt = now,
            )
        )
    }

    override suspend fun updateTarget(id: Id, patch: NotificationTargetPatch): Result<NotificationTarget> = dbQuery {
        NotificationTargetTable.selectAll()
            .where { NotificationTargetTable.id eq id.toString() }
            .firstOrNull()?.toTarget()
            ?: return@dbQuery Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))

        NotificationTargetTable.update({ NotificationTargetTable.id eq id.toString() }) { row ->
            patch.name?.let { n -> row[name] = n }
            patch.url?.let { u -> row[url] = u }
            patch.token?.let { t -> row[token] = t.ifEmpty { null } }
            patch.enabled?.let { e -> row[enabled] = e }
        }

        Result.Success(
            NotificationTargetTable.selectAll()
                .where { NotificationTargetTable.id eq id.toString() }
                .first()
                .toTarget()
        )
    }

    override suspend fun deleteTarget(id: Id): Result<Unit> = dbQuery {
        val deleted = NotificationTargetTable.deleteWhere {
            NotificationTargetTable.id eq id.toString()
        }
        if (deleted == 0) Result.Failure(AppError.NotFound("NotificationTarget", id.toString()))
        else Result.Success(Unit)
    }

    private fun ResultRow.toTarget() = NotificationTarget(
        id = Id.from(this[NotificationTargetTable.id]),
        name = this[NotificationTargetTable.name],
        type = NotificationType.valueOf(this[NotificationTargetTable.type]),
        url = this[NotificationTargetTable.url],
        token = this[NotificationTargetTable.token],
        enabled = this[NotificationTargetTable.enabled],
        createdAt = Instant.parse(this[NotificationTargetTable.createdAt]),
    )
}
