package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PageFormatUtilsTest {
    @Test
    fun `mimeTypeFor maps jpg to image jpeg`() {
        assertEquals("image/jpeg", PageFormatUtils.mimeTypeFor("001.jpg"))
    }

    @Test
    fun `mimeTypeFor maps jpeg to image jpeg`() {
        assertEquals("image/jpeg", PageFormatUtils.mimeTypeFor("page.jpeg"))
    }

    @Test
    fun `mimeTypeFor maps png to image png`() {
        assertEquals("image/png", PageFormatUtils.mimeTypeFor("001.png"))
    }

    @Test
    fun `mimeTypeFor maps webp to image webp`() {
        assertEquals("image/webp", PageFormatUtils.mimeTypeFor("001.webp"))
    }

    @Test
    fun `mimeTypeFor returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", PageFormatUtils.mimeTypeFor("001.xyz"))
    }

    @Test
    fun `mimeTypeFor is case-insensitive`() {
        assertEquals("image/jpeg", PageFormatUtils.mimeTypeFor("001.JPG"))
        assertEquals("image/png", PageFormatUtils.mimeTypeFor("001.PNG"))
    }

    @Test
    fun `buildPageIndex filters non-image entries and sorts by integer stem`() {
        val input = listOf("002.jpg", "README.txt", "000.jpg", "001.png")
        val result = PageFormatUtils.buildPageIndex(input)
        assertEquals(listOf("000.jpg", "001.png", "002.jpg"), result)
    }

    @Test
    fun `buildPageIndex returns empty list for empty input`() {
        assertTrue(PageFormatUtils.buildPageIndex(emptyList()).isEmpty())
    }
}

class FileStorageTest {
    private val libraryPath = Files.createTempDirectory("chaptervault-library-test")
    private val thumbnailsPath = Files.createTempDirectory("chaptervault-thumbnails-test")
    private val storage = FileStorage(libraryPath, thumbnailsPath, ArchiveWriterSelector(emptyList()))

    private fun minimalPng(): ByteArray {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFF0000)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun chapter(
        seriesId: String,
        chapterId: String,
    ) = Chapter(
        id = Id.from(chapterId),
        seriesId = Id.from(seriesId),
        title = "Chapter",
        chapterIndex = 1.0,
        externalId = "ext-001",
        downloadStatus = DownloadStatus.DOWNLOADED,
        addedAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun cbzPath(
        seriesId: String,
        chapterId: String,
    ) = libraryPath.resolve(seriesId).resolve("$chapterId.cbz")

    private fun writeCbz(
        seriesId: String,
        chapterId: String,
        pages: List<Pair<String, ByteArray>>,
    ) {
        val path = cbzPath(seriesId, chapterId)
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, data) in pages) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    private fun writeFolder(
        seriesId: String,
        chapterId: String,
        pages: List<Pair<String, ByteArray>>,
    ) {
        val dir = libraryPath.resolve(seriesId).resolve(chapterId)
        Files.createDirectories(dir)
        for ((name, data) in pages) {
            Files.write(dir.resolve(name), data)
        }
    }

    // --- readPages ---

    @Test
    fun `readPages on CBZ returns pages sorted by integer index with correct bytes and MIME type`() =
        runBlocking {
            val sid = "10000000-0000-0000-0000-000000000001"
            val cid = "20000000-0000-0000-0000-000000000001"
            writeCbz(sid, cid, listOf("002.jpg" to byteArrayOf(3), "000.jpg" to byteArrayOf(1), "001.png" to byteArrayOf(2)))

            val result = storage.readPages(chapter(sid, cid))
            assertIs<Result.Success<List<Page>>>(result)
            val pages = (result as Result.Success).value

            assertEquals(3, pages.size)
            assertEquals(0, pages[0].index)
            assertContentEquals(byteArrayOf(1), pages[0].data)
            assertEquals("image/jpeg", pages[0].mimeType)
            assertEquals(1, pages[1].index)
            assertContentEquals(byteArrayOf(2), pages[1].data)
            assertEquals("image/png", pages[1].mimeType)
            assertEquals(2, pages[2].index)
            assertContentEquals(byteArrayOf(3), pages[2].data)
            assertEquals("image/jpeg", pages[2].mimeType)
        }

    @Test
    fun `readPages on Folder returns pages sorted by integer index with correct bytes and MIME type`() =
        runBlocking {
            val sid = "10000000-0000-0000-0000-000000000002"
            val cid = "20000000-0000-0000-0000-000000000002"
            writeFolder(sid, cid, listOf("001.jpg" to byteArrayOf(10, 20), "000.png" to byteArrayOf(5, 6)))

            val result = storage.readPages(chapter(sid, cid))
            assertIs<Result.Success<List<Page>>>(result)
            val pages = (result as Result.Success).value

            assertEquals(2, pages.size)
            assertEquals(0, pages[0].index)
            assertContentEquals(byteArrayOf(5, 6), pages[0].data)
            assertEquals("image/png", pages[0].mimeType)
            assertEquals(1, pages[1].index)
            assertContentEquals(byteArrayOf(10, 20), pages[1].data)
            assertEquals("image/jpeg", pages[1].mimeType)
        }

    @Test
    fun `readPages returns NotFound when path does not exist`() =
        runBlocking {
            val result =
                storage.readPages(
                    chapter("ffffffff-0000-0000-0000-000000000000", "ffffffff-0000-0000-0000-000000000001"),
                )
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }

    // --- readPage ---

    @Test
    fun `readPage on CBZ returns the page at the given zero-based index`() =
        runBlocking {
            val sid = "10000000-0000-0000-0000-000000000003"
            val cid = "20000000-0000-0000-0000-000000000003"
            writeCbz(sid, cid, listOf("000.jpg" to byteArrayOf(1), "001.jpg" to byteArrayOf(2), "002.jpg" to byteArrayOf(3)))

            val result = storage.readPage(chapter(sid, cid), 1)
            assertIs<Result.Success<Page>>(result)
            val page = (result as Result.Success).value
            assertEquals(1, page.index)
            assertContentEquals(byteArrayOf(2), page.data)
            assertEquals("image/jpeg", page.mimeType)
        }

    @Test
    fun `readPage on Folder returns the page at the given zero-based index`() =
        runBlocking {
            val sid = "10000000-0000-0000-0000-000000000004"
            val cid = "20000000-0000-0000-0000-000000000004"
            writeFolder(sid, cid, listOf("000.jpg" to byteArrayOf(10), "001.png" to byteArrayOf(20)))

            val result = storage.readPage(chapter(sid, cid), 0)
            assertIs<Result.Success<Page>>(result)
            val page = (result as Result.Success).value
            assertEquals(0, page.index)
            assertContentEquals(byteArrayOf(10), page.data)
        }

    @Test
    fun `readPage returns NotFound when index is out of range`() =
        runBlocking {
            val sid = "10000000-0000-0000-0000-000000000005"
            val cid = "20000000-0000-0000-0000-000000000005"
            writeCbz(sid, cid, listOf("000.jpg" to byteArrayOf(1)))

            val result = storage.readPage(chapter(sid, cid), 5)
            assertIs<Result.Failure>(result)
            assertIs<AppError.NotFound>((result as Result.Failure).error)
        }

    // --- deleteSeriesFiles ---

    @Test
    fun `deleteSeriesFiles removes the series directory from libraryPath`() {
        val seriesId = "30000000-0000-0000-0000-000000000001"
        val seriesDir = libraryPath.resolve(seriesId)
        Files.createDirectories(seriesDir.resolve("chapter-1"))
        Files.write(seriesDir.resolve("chapter-1").resolve("001.jpg"), byteArrayOf(1))

        storage.deleteSeriesFiles(seriesId)

        assertFalse(Files.exists(seriesDir))
    }

    @Test
    fun `deleteSeriesFiles does not throw when path does not exist`() {
        storage.deleteSeriesFiles("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    }

    // --- writeCover / readCover ---

    @Test
    fun `writeCover transcodes to JPEG and writes to thumbnailsPath`() {
        val seriesId = "40000000-0000-0000-0000-000000000001"
        storage.writeCover(seriesId, minimalPng())
        val file = thumbnailsPath.resolve("$seriesId.jpg")
        assertTrue(Files.isRegularFile(file))
        val bytes = Files.readAllBytes(file)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
    }

    @Test
    fun `readCover returns JPEG bytes and image-jpeg MIME type`() {
        val seriesId = "40000000-0000-0000-0000-000000000002"
        storage.writeCover(seriesId, minimalPng())
        val result = storage.readCover(seriesId)
        assertIs<Result.Success<Pair<ByteArray, String>>>(result)
        val (_, mimeType) = (result as Result.Success).value
        assertEquals("image/jpeg", mimeType)
    }

    @Test
    fun `readCover returns NotFound when no cover exists`() {
        val result = storage.readCover("ffffffff-ffff-ffff-ffff-ffffffffffff")
        assertIs<Result.Failure>(result)
        assertIs<AppError.NotFound>((result as Result.Failure).error)
    }

    // --- cleanupOrphanedThumbnails ---

    @Test
    fun `cleanupOrphanedThumbnails removes thumbnails for series not in the known set`() {
        val knownId = "50000000-0000-0000-0000-000000000001"
        val orphanId = "50000000-0000-0000-0000-000000000002"
        storage.writeCover(knownId, minimalPng())
        storage.writeCover(orphanId, minimalPng())

        storage.cleanupOrphanedThumbnails(setOf(knownId))

        assertTrue(Files.isRegularFile(thumbnailsPath.resolve("$knownId.jpg")))
        assertFalse(Files.isRegularFile(thumbnailsPath.resolve("$orphanId.jpg")))
    }
}
