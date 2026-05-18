package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskReadStore
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class TaskRepository : TaskReadStore {

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun insert(task: Task): Result<Task> = dbQuery {
        val existing = TaskTable.selectAll()
            .where { TaskTable.id eq task.id.toString() }
            .singleOrNull()
        if (existing != null) return@dbQuery Result.Success(existing.toTask())
        TaskTable.insert {
            it[id] = task.id.toString()
            it[type] = task.type.name
            it[status] = task.status.name
            it[targetType] = task.targetType.name
            it[targetId] = task.targetId.toString()
            it[payload] = Json.encodeToString(task.payload)
            it[createdAt] = task.createdAt.toKotlinInstant()
            it[updatedAt] = task.updatedAt.toKotlinInstant()
            it[errorMessage] = task.errorMessage
        }
        val row = TaskTable.selectAll()
            .where { TaskTable.id eq task.id.toString() }
            .single()
        Result.Success(row.toTask())
    }

    suspend fun updateStatus(id: Id, status: TaskStatus, errorMessage: String? = null): Result<Task> = dbQuery {
        val count = TaskTable.selectAll()
            .where { TaskTable.id eq id.toString() }
            .count()
        if (count == 0L) return@dbQuery Result.Failure(AppError.NotFound("Task", id.toString()))

        TaskTable.update({ TaskTable.id eq id.toString() }) {
            it[TaskTable.status] = status.name
            it[TaskTable.errorMessage] = errorMessage
            it[TaskTable.updatedAt] = Instant.now().toKotlinInstant()
        }

        val row = TaskTable.selectAll()
            .where { TaskTable.id eq id.toString() }
            .single()
        Result.Success(row.toTask())
    }

    suspend fun findById(id: Id): Result<Task> = dbQuery {
        val row = TaskTable.selectAll()
            .where { TaskTable.id eq id.toString() }
            .singleOrNull()
            ?: return@dbQuery Result.Failure(AppError.NotFound("Task", id.toString()))
        Result.Success(row.toTask())
    }

    override suspend fun listTasks(request: PageRequest): Result<Pagination<Task>> = dbQuery {
        val total = TaskTable.selectAll().count()
        val items = TaskTable.selectAll()
            .orderBy(TaskTable.createdAt, SortOrder.DESC)
            .limit(request.size)
            .offset((request.page.toLong() * request.size))
            .map { it.toTask() }
        Result.Success(Pagination(items, request.page, request.size, total))
    }

    suspend fun listByStatus(status: TaskStatus, request: PageRequest): Result<Pagination<Task>> = dbQuery {
        val total = TaskTable.selectAll()
            .where { TaskTable.status eq status.name }
            .count()
        val items = TaskTable.selectAll()
            .where { TaskTable.status eq status.name }
            .orderBy(TaskTable.createdAt, SortOrder.DESC)
            .limit(request.size)
            .offset((request.page.toLong() * request.size))
            .map { it.toTask() }
        Result.Success(Pagination(items, request.page, request.size, total))
    }

    suspend fun listAllByStatus(status: TaskStatus): List<Task> = dbQuery {
        TaskTable.selectAll()
            .where { TaskTable.status eq status.name }
            .orderBy(TaskTable.createdAt, SortOrder.ASC)
            .map { it.toTask() }
    }

    override suspend fun findTask(id: Id): Result<Task> = findById(id)

    private fun ResultRow.toTask() = Task(
        id = Id.from(this[TaskTable.id]),
        type = TaskType.valueOf(this[TaskTable.type]),
        status = TaskStatus.valueOf(this[TaskTable.status]),
        targetType = TargetType.valueOf(this[TaskTable.targetType]),
        targetId = Id.from(this[TaskTable.targetId]),
        payload = Json.decodeFromString(this[TaskTable.payload]),
        createdAt = this[TaskTable.createdAt].toJavaInstant(),
        updatedAt = this[TaskTable.updatedAt].toJavaInstant(),
        errorMessage = this[TaskTable.errorMessage],
    )
}
