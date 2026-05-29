package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.DatabaseMigrations
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UserSeriesStatusRepositoryTest {

    private lateinit var repo: UserSeriesStatusRepository

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("test.db")}", "org.sqlite.JDBC")
        DatabaseMigrations.migrate(db)
        repo = UserSeriesStatusRepository()
    }

    @Test
    fun `setStatus stores status as string name`() = runBlocking {
        val userId = Id.generate()
        val seriesId = Id.generate()
        val result = repo.setStatus(userId, seriesId, ReadingStatus.READING)
        assertIs<Result.Success<Unit>>(result)
        assertEquals(ReadingStatus.READING, repo.getStatus(userId, seriesId))
    }

    @Test
    fun `setStatus is idempotent — second call updates existing row`() = runBlocking {
        val userId = Id.generate()
        val seriesId = Id.generate()
        repo.setStatus(userId, seriesId, ReadingStatus.READING)
        repo.setStatus(userId, seriesId, ReadingStatus.COMPLETED)
        assertEquals(ReadingStatus.COMPLETED, repo.getStatus(userId, seriesId))
    }

    @Test
    fun `clearStatus removes the row`() = runBlocking {
        val userId = Id.generate()
        val seriesId = Id.generate()
        repo.setStatus(userId, seriesId, ReadingStatus.ON_HOLD)
        repo.clearStatus(userId, seriesId)
        assertNull(repo.getStatus(userId, seriesId))
    }

    @Test
    fun `getStatus returns null when no status set`() = runBlocking {
        assertNull(repo.getStatus(Id.generate(), Id.generate()))
    }

    @Test
    fun `status is isolated per user`() = runBlocking {
        val user1 = Id.generate()
        val user2 = Id.generate()
        val seriesId = Id.generate()
        repo.setStatus(user1, seriesId, ReadingStatus.READING)
        assertNull(repo.getStatus(user2, seriesId))
    }
}
