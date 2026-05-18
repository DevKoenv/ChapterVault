package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.TaskTable
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
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
    ): Task = Task(
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
    fun `listByStatus returns only tasks with matching status`() {
        runBlocking {
            repo.insert(buildTask(status = TaskStatus.PENDING))
            repo.insert(buildTask(status = TaskStatus.PENDING))
            val runningTask = buildTask(status = TaskStatus.RUNNING)
            repo.insert(runningTask)

            val result = repo.listByStatus(TaskStatus.PENDING, PageRequest(page = 0, size = 20))
            val page = assertIs<Result.Success<*>>(result).value as dev.koenv.chaptervault.shared.paging.Pagination<*>
            assertEquals(2, page.items.size)
            assertEquals(2L, page.totalItems)

            val resultRunning = repo.listByStatus(TaskStatus.RUNNING, PageRequest(page = 0, size = 20))
            val pageRunning = assertIs<Result.Success<*>>(resultRunning).value as dev.koenv.chaptervault.shared.paging.Pagination<*>
            assertEquals(1, pageRunning.items.size)
        }
    }
}
