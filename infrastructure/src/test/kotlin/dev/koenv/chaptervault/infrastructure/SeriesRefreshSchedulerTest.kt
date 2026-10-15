package dev.koenv.chaptervault.infrastructure

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.SeriesQuery
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Series
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.Task
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskStatus
import dev.koenv.chaptervault.shared.paging.PageRequest
import dev.koenv.chaptervault.shared.paging.Pagination
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesRefreshSchedulerTest {
    private val hour = 3_600_000L

    private class RecordingQueue(
        private val delegate: TaskQueue = InMemoryTaskQueue(),
    ) : TaskQueue by delegate {
        val enqueued = mutableListOf<Task>()

        override suspend fun enqueue(task: Task): Result<Id> {
            enqueued += task
            return delegate.enqueue(task)
        }
    }

    private fun series() =
        Series(
            id = Id.generate(),
            title = "S",
            connectorId = "mangadex",
            externalId = "x",
            status = SeriesStatus.IN_LIBRARY,
            addedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private class FakeLibrary(
        private val all: List<Series>,
    ) : LibraryReadApi {
        override suspend fun listSeries(request: PageRequest) =
            Result.Success(
                Pagination(all.drop(request.page * request.size).take(request.size), request.page, request.size, all.size.toLong()),
            )

        override suspend fun listSeries(
            query: SeriesQuery,
            request: PageRequest,
        ): Result<Pagination<Series>> = error("unused")

        override suspend fun getSeries(id: Id): Result<Series> = error("unused")

        override suspend fun listSeriesByIds(
            ids: Collection<Id>,
            request: PageRequest,
        ): Result<Pagination<Series>> = error("unused")

        override suspend fun searchLibrary(
            query: String,
            request: PageRequest,
        ): Result<Pagination<Series>> = error("unused")

        override suspend fun getChapter(id: Id): Result<Chapter> = error("unused")

        override suspend fun listChapters(seriesId: Id): Result<List<Chapter>> = error("unused")

        override suspend fun listChaptersByStatus(
            seriesId: Id,
            status: DownloadStatus,
        ): Result<List<Chapter>> = error("unused")

        override suspend fun listChaptersPaginated(
            seriesId: Id,
            status: DownloadStatus?,
            request: PageRequest,
        ): Result<Pagination<Chapter>> = error("unused")

        override suspend fun inLibraryExternalIds(
            connectorId: String,
            externalIds: List<String>,
        ): Result<Set<String>> = error("unused")
    }

    @Test
    fun `first refresh does not wait a whole interval after start`() =
        runTest {
            val queue = RecordingQueue()
            val scheduler = SeriesRefreshScheduler(FakeLibrary(listOf(series())), queue, intervalHours = 24)
            backgroundScope.launch { scheduler.start() }

            advanceTimeBy(2 * 60_000L)

            assertEquals(1, queue.enqueued.size)
        }

    @Test
    fun `a series whose refresh is still queued is not queued again`() =
        runTest {
            val queue = RecordingQueue()
            val scheduler = SeriesRefreshScheduler(FakeLibrary(listOf(series())), queue, intervalHours = 1)
            backgroundScope.launch { scheduler.start() }

            advanceTimeBy(2 * 60_000L + 2 * hour)

            assertEquals(1, queue.enqueued.size)
        }

    @Test
    fun `a series is queued again once its earlier refresh is finished`() =
        runTest {
            val queue = RecordingQueue()
            val scheduler = SeriesRefreshScheduler(FakeLibrary(listOf(series())), queue, intervalHours = 1)
            backgroundScope.launch { scheduler.start() }

            advanceTimeBy(2 * 60_000L)
            queue.forget(queue.enqueued.single().id)
            advanceTimeBy(hour)

            assertEquals(2, queue.enqueued.size)
            assertEquals(TaskStatus.PENDING, queue.enqueued.last().status)
        }
}
