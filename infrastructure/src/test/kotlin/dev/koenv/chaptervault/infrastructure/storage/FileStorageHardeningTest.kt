package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.DownloadStatus
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileStorageHardeningTest {
    private val libraryPath = Files.createTempDirectory("cv-hardening-library")
    private val thumbnailsPath = Files.createTempDirectory("cv-hardening-thumbs")
    private val storage =
        FileStorage(libraryPath, thumbnailsPath, ArchiveWriterSelector(listOf(CbzWriter(), FolderWriter())))

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 9)
    private val webp = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    private val seriesId = Id.generate()
    private val chapterId = Id.generate()

    private fun chapter() =
        Chapter(
            id = chapterId,
            seriesId = seriesId,
            title = "Chapter",
            chapterIndex = 1.0,
            externalId = "ext",
            downloadStatus = DownloadStatus.DOWNLOADED,
            addedAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun write(
        vararg pages: Page,
        format: ChapterFormat = ChapterFormat.Cbz,
    ) = runBlocking { storage.writeChapter(seriesId.toString(), chapterId.toString(), pages.toList(), format) }

    private fun readPage(index: Int) = runBlocking { storage.readPage(chapter(), index) }

    // ---- H7 ------------------------------------------------------------------------------------------------------

    @Test
    fun `a negative page index is NotFound for a CBZ chapter`() {
        write(Page(0, jpeg))

        assertIs<AppError.NotFound>((readPage(-1) as Result.Failure).error)
    }

    @Test
    fun `a negative page index is NotFound for a folder chapter`() {
        write(Page(0, jpeg), format = ChapterFormat.Folder)

        assertIs<AppError.NotFound>((readPage(-1) as Result.Failure).error)
    }

    // ---- H2 ------------------------------------------------------------------------------------------------------

    @Test
    fun `a foreign directory in the library path survives orphan cleanup`() {
        val foreign = Files.createDirectories(libraryPath.resolve("My Photos"))
        foreign.resolve("holiday.jpg").writeText("precious")

        storage.cleanupOrphanedDirs(emptySet())

        assertEquals("precious", foreign.resolve("holiday.jpg").readText())
    }

    @Test
    fun `a foreign file in the library path survives orphan cleanup`() {
        val file = libraryPath.resolve("notes.txt").also { it.writeText("keep") }

        storage.cleanupOrphanedDirs(emptySet())

        assertEquals("keep", file.readText())
    }

    @Test
    fun `an unknown series directory is moved to trash instead of deleted`() {
        val orphan = Files.createDirectories(libraryPath.resolve(Id.generate().toString()))
        orphan.resolve("a.cbz").writeText("data")

        storage.cleanupOrphanedDirs(emptySet())

        assertFalse(orphan.exists())
        val trashed = libraryPath.resolve(".trash").listDirectoryEntries().single()
        assertTrue(trashed.name.startsWith(orphan.name))
        assertEquals("data", trashed.resolve("a.cbz").readText())
    }

    @Test
    fun `a known series directory is left alone`() {
        val known = Files.createDirectories(libraryPath.resolve(seriesId.toString()))
        known.resolve("a.cbz").writeText("data")

        storage.cleanupOrphanedDirs(setOf(seriesId.toString()))

        assertEquals("data", known.resolve("a.cbz").readText())
        assertFalse(libraryPath.resolve(".trash").exists())
    }

    @Test
    fun `trash is not trashed again by a later cleanup`() {
        Files.createDirectories(libraryPath.resolve(Id.generate().toString()))
        storage.cleanupOrphanedDirs(emptySet())
        val before = libraryPath.resolve(".trash").listDirectoryEntries().map { it.name }

        storage.cleanupOrphanedDirs(emptySet())

        assertEquals(before, libraryPath.resolve(".trash").listDirectoryEntries().map { it.name })
    }

    @Test
    fun `an unrelated image in the thumbnails path survives thumbnail cleanup`() {
        val foreign = thumbnailsPath.resolve("wallpaper.jpg").also { it.writeText("mine") }
        val orphan = thumbnailsPath.resolve("${Id.generate()}.jpg").also { it.writeText("cover") }

        storage.cleanupOrphanedThumbnails(emptySet())

        assertTrue(foreign.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `leftover partial writes are swept`() {
        val seriesDir = Files.createDirectories(libraryPath.resolve(seriesId.toString()))
        val partFile = seriesDir.resolve("$chapterId.cbz.part").also { it.writeText("half") }
        val partDir = Files.createDirectories(seriesDir.resolve("$chapterId.part")).also { it.resolve("000.jpg").writeText("half") }
        val keep = seriesDir.resolve("${Id.generate()}.cbz").also { it.writeText("whole") }

        storage.sweepPartialWrites()

        assertFalse(partFile.exists())
        assertFalse(partDir.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `deleting a series also deletes its cover`() {
        val cover = thumbnailsPath.resolve("$seriesId.jpg").also { it.writeText("cover") }
        Files.createDirectories(libraryPath.resolve(seriesId.toString()))

        storage.deleteSeriesFiles(seriesId.toString())

        assertFalse(cover.exists())
    }

    // ---- H12 (covers) --------------------------------------------------------------------------------------------

    @Test
    fun `a cover that is not an image is not stored`() {
        assertFailsWith<IllegalArgumentException> { storage.writeCover(seriesId.toString(), "<html>".toByteArray()) }

        assertFalse(thumbnailsPath.resolve("$seriesId.jpg").exists())
    }

    @Test
    fun `a stored cover is served with the type its bytes have`() {
        storage.writeCover(seriesId.toString(), webp)

        val (_, mime) = (storage.readCover(seriesId.toString()) as Result.Success).value
        assertEquals("image/webp", mime)
    }

    // ---- H19 -----------------------------------------------------------------------------------------------------

    private class FailingList(
        private val ok: List<Page>,
    ) : AbstractList<Page>() {
        override val size = ok.size + 1

        override fun get(index: Int): Page = if (index < ok.size) ok[index] else throw IOException("disk full")
    }

    private fun cbz(): Path = libraryPath.resolve(seriesId.toString()).resolve("$chapterId.cbz")

    @Test
    fun `a failed CBZ write leaves neither an archive nor a partial file`() {
        val result =
            runBlocking {
                storage.writeChapter(seriesId.toString(), chapterId.toString(), FailingList(listOf(Page(0, jpeg))), ChapterFormat.Cbz)
            }

        assertIs<Result.Failure>(result)
        assertFalse(cbz().exists())
        assertEquals(emptyList(), cbz().parent.listDirectoryEntries())
    }

    @Test
    fun `a failed rewrite keeps the previous archive intact`() {
        write(Page(0, jpeg))
        val before = Files.readAllBytes(cbz())

        runBlocking {
            storage.writeChapter(seriesId.toString(), chapterId.toString(), FailingList(listOf(Page(0, png))), ChapterFormat.Cbz)
        }

        assertContentEquals(before, Files.readAllBytes(cbz()))
        assertEquals(listOf(cbz().name), cbz().parent.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a failed folder write leaves nothing behind`() {
        val result =
            runBlocking {
                storage.writeChapter(seriesId.toString(), chapterId.toString(), FailingList(listOf(Page(0, jpeg))), ChapterFormat.Folder)
            }

        assertIs<Result.Failure>(result)
        assertEquals(emptyList(), libraryPath.resolve(seriesId.toString()).listDirectoryEntries())
    }

    @Test
    fun `a successful write leaves no partial file`() {
        write(Page(0, jpeg), Page(1, jpeg))

        assertEquals(listOf(cbz().name), cbz().parent.listDirectoryEntries().map { it.name })
    }

    // ---- H18 -----------------------------------------------------------------------------------------------------

    @Test
    fun `sniffing recognises the supported image types`() {
        assertEquals("image/jpeg", PageFormatUtils.sniffMimeType(jpeg))
        assertEquals("image/png", PageFormatUtils.sniffMimeType(png))
        assertEquals("image/webp", PageFormatUtils.sniffMimeType(webp))
        assertEquals(null, PageFormatUtils.sniffMimeType("<html>".toByteArray()))
        assertEquals(null, PageFormatUtils.sniffMimeType(ByteArray(0)))
    }

    @Test
    fun `PNG and WebP pages keep their type through a CBZ round trip`() {
        write(Page(0, jpeg), Page(1, png), Page(2, webp))

        assertEquals(listOf("image/jpeg", "image/png", "image/webp"), (0..2).map { (readPage(it) as Result.Success).value.mimeType })
        ZipFile(cbz().toFile()).use { zip ->
            assertEquals(listOf("000.jpg", "001.png", "002.webp"), zip.entries().toList().map { it.name })
        }
    }

    @Test
    fun `PNG and WebP pages keep their type through a folder round trip`() {
        write(Page(0, png), Page(1, webp), format = ChapterFormat.Folder)

        assertEquals(listOf("image/png", "image/webp"), (0..1).map { (readPage(it) as Result.Success).value.mimeType })
        val page = (readPage(0) as Result.Success).value
        assertContentEquals(png, page.data)
    }

    @Test
    fun `unrecognised bytes fall back to the type the connector declared`() {
        write(Page(0, byteArrayOf(1, 2, 3), mimeType = "image/png"))

        assertEquals("image/png", (readPage(0) as Result.Success).value.mimeType)
    }

    // ---- H17 -----------------------------------------------------------------------------------------------------

    @Test
    fun `reading pages from a folder chapter does not leak directory handles`() {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val counter = os.javaClass.methods.firstOrNull { it.name == "getOpenFileDescriptorCount" }
        assumeTrue(counter != null, "open descriptor count is only available on Unix")

        fun open() = (counter!!.invoke(os) as Long)
        write(Page(0, jpeg), Page(1, png), format = ChapterFormat.Folder)
        readPage(0) // warm up

        val before = open()
        repeat(400) {
            readPage(it % 2)
            runBlocking { storage.countPages(chapter()) }
            runBlocking { storage.readPages(chapter()) }
        }

        assertTrue(open() - before < 50, "descriptors grew from $before to ${open()}")
    }

    // ---- H21 -----------------------------------------------------------------------------------------------------

    @Test
    fun `the page index follows the archive when a chapter is downloaded again`() {
        write(Page(0, jpeg), Page(1, jpeg))
        assertIs<Result.Success<*>>(readPage(1))
        assertIs<Result.Failure>(readPage(2))

        // keep the same mtime window as possible: the cache must key on size as well
        write(Page(0, jpeg), Page(1, jpeg), Page(2, png))

        assertEquals("image/png", (readPage(2) as Result.Success).value.mimeType)
    }

    @Test
    fun `reading a page from a missing chapter is NotFound`() {
        assertIs<AppError.NotFound>((readPage(0) as Result.Failure).error)
    }
}
