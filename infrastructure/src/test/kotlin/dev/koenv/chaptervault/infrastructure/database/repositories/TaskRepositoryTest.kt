package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQuery
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskRepositoryTest {
    private val repo = TaskRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbFile = Files.createTempFile("chaptervault-test", ".sqlite").toFile()
            dbFile.deleteOnExit()
            Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(TaskTable)
            }
        }
    }

    @AfterEach
    fun cleanTables() {
        transaction {
            SchemaUtils.drop(TaskTable)
            SchemaUtils.create(TaskTable)
        }
    }

    private fun buildTask(
        status: TaskStatus = TaskStatus.PENDING,
        payload: Map<String, String> = emptyMap(),
    ): Task =
        Task(
            id = Id.generate(),
            type = TaskType.DOWNLOAD_CHAPTER,
            status = status,
            targetType = TargetType.CHAPTER,
            targetId = Id.generate(),
            payload = payload,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `deleteFinishedBefore removes only finished tasks older than the cutoff`() =
        runBlocking<Unit> {
            val now = Instant.now()
            val old = now.minusSeconds(40L * 24 * 3600)
            val oldDone = buildTask(TaskStatus.COMPLETED).copy(updatedAt = old)
            val oldFailed = buildTask(TaskStatus.FAILED).copy(updatedAt = old)
            val oldCancelled = buildTask(TaskStatus.CANCELLED).copy(updatedAt = old)
            val oldPending = buildTask(TaskStatus.PENDING).copy(updatedAt = old)
            val oldRunning = buildTask(TaskStatus.RUNNING).copy(updatedAt = old)
            val freshDone = buildTask(TaskStatus.COMPLETED).copy(updatedAt = now)
            listOf(oldDone, oldFailed, oldCancelled, oldPending, oldRunning, freshDone).forEach { repo.insert(it) }

            val removed = repo.deleteFinishedBefore(now.minusSeconds(30L * 24 * 3600))

            assertEquals(3, removed)
            val left =
                (repo.listTasks(PageRequest(0, 50)) as Result.Success)
                    .value.items
                    .map { it.id }
                    .toSet()
            assertEquals(setOf(oldPending.id, oldRunning.id, freshDone.id), left)
        }

    @Test
    fun `insert persists task and returns it`() {
        runBlocking {
            val task = buildTask(payload = mapOf("key" to "value"))
            val result = repo.insert(task)
            val inserted = assertIs<Result.Success<Task>>(result).value
            assertEquals(task.id, inserted.id)
            assertEquals(task.type, inserted.type)
            assertEquals(task.status, inserted.status)
            assertEquals(task.targetType, inserted.targetType)
            assertEquals(task.targetId, inserted.targetId)
            assertEquals(mapOf("key" to "value"), inserted.payload)
        }
    }

    @Test
    fun `updateStatus changes status and clears errorMessage`() {
        runBlocking {
            val task = buildTask()
            repo.insert(task)

            val result = repo.updateStatus(task.id, TaskStatus.RUNNING)
            val updated = assertIs<Result.Success<Task>>(result).value
            assertEquals(TaskStatus.RUNNING, updated.status)
            assertEquals(null, updated.errorMessage)
        }
    }

    @Test
    fun `updateStatus sets errorMessage when provided`() {
        runBlocking {
            val task = buildTask()
            repo.insert(task)

            val result = repo.updateStatus(task.id, TaskStatus.FAILED, "something went wrong")
            val updated = assertIs<Result.Success<Task>>(result).value
            assertEquals(TaskStatus.FAILED, updated.status)
            assertEquals("something went wrong", updated.errorMessage)
        }
    }

    @Test
    fun `updateStatus with unknown id returns NotFound failure`() {
        runBlocking {
            val result = repo.updateStatus(Id.generate(), TaskStatus.RUNNING)
            val failure = assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>(failure.error)
        }
    }

    @Test
    fun `findById returns NotFound for unknown id`() {
        runBlocking {
            val result = repo.findById(Id.generate())
            val failure = assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>(failure.error)
        }
    }

    @Test
    fun `findById returns task for known id`() {
        runBlocking {
            val task = buildTask()
            repo.insert(task)

            val result = repo.findById(task.id)
            val found = assertIs<Result.Success<Task>>(result).value
            assertEquals(task.id, found.id)
        }
    }

    @Test
    fun `listAll paginates correctly`() {
        runBlocking {
            repo.insert(buildTask())
            repo.insert(buildTask())
            repo.insert(buildTask())

            val result = repo.listTasks(PageRequest(page = 0, size = 2))
            val page = assertIs<Result.Success<*>>(result).value as dev.koenv.chaptervault.shared.paging.Pagination<*>
            assertEquals(2, page.items.size)
            assertEquals(3L, page.totalItems)
            assertEquals(0, page.page)
            assertEquals(2, page.size)
        }
    }

    @Test
    fun `listAll second page returns remaining items`() {
        runBlocking {
            repo.insert(buildTask())
            repo.insert(buildTask())
            repo.insert(buildTask())

            val result = repo.listTasks(PageRequest(page = 1, size = 2))
            val page = assertIs<Result.Success<*>>(result).value as dev.koenv.chaptervault.shared.paging.Pagination<*>
            assertEquals(1, page.items.size)
            assertEquals(3L, page.totalItems)
        }
    }

    @Test
    fun `listTasks filters on status, type and target and combines them`() =
        runBlocking {
            val target = Id.generate()
            val failedDownload = buildTask(TaskStatus.FAILED).copy(targetId = target)
            val failedRefresh = buildTask(TaskStatus.FAILED).copy(type = TaskType.FETCH_CHAPTERS, targetType = TargetType.SERIES)
            val pendingDownload = buildTask(TaskStatus.PENDING).copy(targetId = target)
            listOf(failedDownload, failedRefresh, pendingDownload).forEach { repo.insert(it) }

            suspend fun ids(query: TaskQuery) =
                (repo.listTasks(query, PageRequest(0, 50)) as Result.Success)
                    .value.items
                    .map { it.id }
                    .toSet()

            assertEquals(setOf(failedDownload.id, failedRefresh.id), ids(TaskQuery(status = TaskStatus.FAILED)))
            assertEquals(setOf(failedRefresh.id), ids(TaskQuery(type = TaskType.FETCH_CHAPTERS)))
            assertEquals(setOf(failedDownload.id, pendingDownload.id), ids(TaskQuery(targetId = target)))
            val combined =
                (
                    repo.listTasks(
                        TaskQuery(status = TaskStatus.FAILED, targetId = target),
                        PageRequest(0, 50),
                    ) as Result.Success
                ).value
            assertEquals(listOf(failedDownload.id), combined.items.map { it.id })
            assertEquals(1L, combined.totalItems)
        }

    @Test
    fun `tasks created at the same moment keep a stable order across pages`() =
        runBlocking {
            val moment = Instant.parse("2026-10-15T12:00:00Z")
            repeat(5) { repo.insert(buildTask().copy(createdAt = moment, updatedAt = moment)) }

            val ids =
                (0..2).flatMap {
                    (
                        repo.listTasks(
                            TaskQuery(),
                            PageRequest(it, 2),
                        ) as Result.Success
                    ).value.items.map { t -> t.id.toString() }
                }

            assertEquals(5, ids.toSet().size)
            assertEquals(ids.sorted(), ids)
        }
}
