package dev.koenv.chaptervault.infrastructure.database

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.repositories.TaskRepository
import dev.koenv.chaptervault.kernel.runtime.TargetType
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class DatabaseConcurrencyTest {
    /**
     * Repositories read and then write inside one transaction. With SQLite's default deferred transactions, a second
     * writer committing in between makes the first one fail at once with SQLITE_BUSY (the busy timeout does not apply
     * to a stale read snapshot). Workers and API requests write at the same time, so this has to work.
     */
    @Test
    fun `concurrent read-then-write transactions all succeed`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("concurrent.db")}"))
        val tasks = TaskRepository()
        val ids = List(8) { Id.generate() }
        ids.forEach {
            tasks.insert(
                Task(
                    it,
                    TaskType.DOWNLOAD_CHAPTER,
                    TaskStatus.PENDING,
                    TargetType.CHAPTER,
                    Id.generate(),
                    emptyMap(),
                    Instant.now(),
                    Instant.now(),
                ),
            )
        }

        val results =
            ids
                .map { id ->
                    async(Dispatchers.IO) {
                        List(25) { tasks.updateStatus(id, if (it % 2 == 0) TaskStatus.RUNNING else TaskStatus.PENDING) }
                    }
                }.awaitAll()
                .flatten()

        assertEquals(8 * 25, results.count { it is Result.Success })
    }
}
