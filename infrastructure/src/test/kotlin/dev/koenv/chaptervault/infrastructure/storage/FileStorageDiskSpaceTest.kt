package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileStorageDiskSpaceTest {
    private class FakeDisk(
        var free: Long,
        val total: Long = 1_000_000_000L,
    ) : DiskSpace {
        override fun usableBytes(path: Path) = free

        override fun totalBytes(path: Path) = total
    }

    private val mb = 1024L * 1024L

    private fun storage(
        dir: Path,
        minFreeBytes: Long,
        disk: DiskSpace,
        writers: List<ChapterArchiveWriter> = listOf(CbzWriter()),
    ) = FileStorage(
        dir.resolve("library"),
        dir.resolve("thumbnails"),
        ArchiveWriterSelector(writers),
        JpegThumbnailFormat,
        minFreeBytes,
        disk,
    )

    private val failingWriter =
        object : ChapterArchiveWriter {
            override val supportedFormat: ChapterFormat = ChapterFormat.Cbz

            override suspend fun write(
                pages: List<Page>,
                destination: Path,
            ): Unit = throw IOException("write error")
        }

    @Test
    fun `the free space check passes above the minimum, fails below it and is off at 0`(
        @TempDir dir: Path,
    ) {
        val disk = FakeDisk(free = 2048 * mb)
        assertIs<Result.Success<Unit>>(storage(dir, 1024 * mb, disk).checkFreeSpace())

        disk.free = 1000 * mb
        val error = (storage(dir, 1024 * mb, disk).checkFreeSpace() as Result.Failure).error
        assertIs<AppError.InsufficientStorage>(error)
        assertContains(error.message, "1000 MB")
        assertContains(error.message, "1024 MB")

        disk.free = 0
        assertIs<Result.Success<Unit>>(storage(dir, 0, disk).checkFreeSpace())
    }

    @Test
    fun `a chapter write that fails on a nearly full disk is reported as insufficient storage`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        val pages = listOf(Page(0, ByteArray(4096)))
        val full = storage(dir, 0, FakeDisk(free = 100), listOf(failingWriter))
        assertIs<AppError.InsufficientStorage>((full.writeChapter("s", "c", pages, ChapterFormat.Cbz) as Result.Failure).error)

        // the same write error with plenty of space is an ordinary failure that is worth retrying
        val roomy = storage(dir, 0, FakeDisk(free = 1024 * mb), listOf(failingWriter))
        assertIs<AppError.InternalError>((roomy.writeChapter("s", "c", pages, ChapterFormat.Cbz) as Result.Failure).error)
    }

    @Test
    fun `storage usage adds up the library and thumbnail files and reports the disk`(
        @TempDir dir: Path,
    ) = runBlocking {
        val library = Files.createDirectories(dir.resolve("library").resolve("series-a"))
        Files.write(library.resolve("c1.cbz"), ByteArray(1000))
        Files.write(library.resolve("c2.cbz"), ByteArray(500))
        Files.write(Files.createDirectories(dir.resolve("thumbnails")).resolve("series-a.jpg"), ByteArray(70))

        val usage = (storage(dir, 1024 * mb, FakeDisk(free = 123, total = 456)).storageUsage() as Result.Success).value

        assertEquals(1500L, usage.libraryBytes)
        assertEquals(70L, usage.thumbnailsBytes)
        assertEquals(123L, usage.freeBytes)
        assertEquals(456L, usage.totalBytes)
        assertEquals(1024 * mb, usage.minFreeBytes)
    }

    @Test
    fun `storage usage of a fresh install without folders is zero`(
        @TempDir dir: Path,
    ) = runBlocking {
        val usage = (storage(dir, 0, FakeDisk(free = 1)).storageUsage() as Result.Success).value
        assertEquals(0L, usage.libraryBytes)
        assertEquals(0L, usage.thumbnailsBytes)
    }
}
