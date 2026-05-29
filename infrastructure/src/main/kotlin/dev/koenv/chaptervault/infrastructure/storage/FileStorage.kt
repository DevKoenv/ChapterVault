package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

open class FileStorage(
    private val libraryPath: Path,
    private val thumbnailsPath: Path,
    private val writerSelector: ArchiveWriterSelector,
    private val thumbnailFormat: ThumbnailFormat = JpegThumbnailFormat,
) : ChapterPageSource {

    private val logger = LoggerFactory.getLogger(FileStorage::class.java)

    fun ensureDirectories() {
        Files.createDirectories(libraryPath)
        Files.createDirectories(thumbnailsPath)
    }

    fun writeCover(seriesId: String, bytes: ByteArray) {
        Files.createDirectories(thumbnailsPath)
        val encoded = thumbnailFormat.encode(bytes)
        Files.write(thumbnailsPath.resolve("$seriesId.${thumbnailFormat.extension}"), encoded)
    }

    fun readCover(seriesId: String): Result<Pair<ByteArray, String>> {
        val file = thumbnailsPath.resolve("$seriesId.${thumbnailFormat.extension}")
        if (!Files.isRegularFile(file)) return Result.Failure(AppError.NotFound("Cover", seriesId))
        return Result.Success(Files.readAllBytes(file) to thumbnailFormat.mimeType)
    }

    fun chapterExists(chapter: Chapter): Boolean {
        val path = chapterPath(chapter)
        return Files.isRegularFile(path) || Files.isDirectory(path)
    }

    // Streams chapter content to out without buffering the full archive in memory.
    // Folder chapters are zipped on-the-fly. Throws if chapter files are not found.
    fun streamChapterTo(chapter: Chapter, out: OutputStream) {
        val path = chapterPath(chapter)
        when {
            Files.isRegularFile(path) -> Files.newInputStream(path).use { it.copyTo(out) }
            Files.isDirectory(path) -> ZipOutputStream(out).use { zip ->
                Files.list(path).sorted().forEach { file ->
                    zip.putNextEntry(ZipEntry(file.fileName.toString()))
                    Files.newInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            else -> throw java.io.FileNotFoundException("Chapter files not found: ${chapter.id}")
        }
    }

    fun resolvePath(seriesId: String, chapterId: String): Path =
        libraryPath.resolve(seriesId).resolve(chapterId)

    private fun chapterPath(chapter: Chapter): Path {
        val base = resolvePath(chapter.seriesId.toString(), chapter.id.toString())
        val cbz = base.resolveSibling(base.fileName.toString() + ".cbz")
        return if (Files.isRegularFile(cbz)) cbz else base
    }

    suspend fun readPages(chapter: Chapter): Result<List<Page>> {
        val path = chapterPath(chapter)
        return when {
            Files.isDirectory(path) -> readPagesFromFolder(path)
            Files.isRegularFile(path) -> readPagesFromCbz(path)
            else -> Result.Failure(AppError.NotFound("Chapter files", chapter.id.toString()))
        }
    }

    override suspend fun readPage(chapter: Chapter, index: Int): Result<Page> {
        val path = chapterPath(chapter)
        return when {
            Files.isDirectory(path) -> readPageFromFolder(path, index)
            Files.isRegularFile(path) -> readPageFromCbz(path, index)
            else -> Result.Failure(AppError.NotFound("Chapter files", chapter.id.toString()))
        }
    }

    override suspend fun countPages(chapter: Chapter): Result<Int> {
        val path = chapterPath(chapter)
        return when {
            Files.isDirectory(path) -> {
                val filenames = Files.list(path).use { it.map { f -> f.fileName.toString() }.toList() }
                Result.Success(PageFormatUtils.buildPageIndex(filenames).size)
            }
            Files.isRegularFile(path) -> {
                val entryNames = mutableListOf<String>()
                ZipFile(path.toFile()).use { zip ->
                    zip.entries().asSequence().mapTo(entryNames) { it.name }
                }
                Result.Success(PageFormatUtils.buildPageIndex(entryNames).size)
            }
            else -> Result.Failure(AppError.NotFound("Chapter files", chapter.id.toString()))
        }
    }

    fun deleteChapterFiles(seriesId: String, chapterId: String) {
        val base = resolvePath(seriesId, chapterId)
        val cbz = base.resolveSibling(base.fileName.toString() + ".cbz")
        try {
            Files.deleteIfExists(cbz)
            if (Files.isDirectory(base)) base.toFile().deleteRecursively()
            else Files.deleteIfExists(base)
        } catch (e: IOException) {
            logger.warn("Failed to delete files for chapter $chapterId: ${e.message}")
        }
    }

    fun cleanupOrphanedDirs(knownSeriesIds: Set<String>) {
        if (!Files.isDirectory(libraryPath)) return
        Files.list(libraryPath).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { it.fileName.toString() !in knownSeriesIds }
                .forEach { dir ->
                    logger.info("Removing orphaned library files for series ${dir.fileName}")
                    dir.toFile().deleteRecursively()
                }
        }
    }

    fun cleanupOrphanedThumbnails(knownSeriesIds: Set<String>) {
        if (!Files.isDirectory(thumbnailsPath)) return
        Files.list(thumbnailsPath).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringBeforeLast(".") !in knownSeriesIds }
                .forEach { file ->
                    logger.info("Removing orphaned thumbnail for series ${file.fileName}")
                    Files.deleteIfExists(file)
                }
        }
    }

    open fun deleteSeriesFiles(seriesId: String) {
        val dir = libraryPath.resolve(seriesId)
        try {
            if (Files.exists(dir)) dir.toFile().deleteRecursively()
        } catch (e: IOException) {
            logger.warn("Failed to delete library files for series $seriesId: ${e.message}")
        }
    }

    suspend fun writeChapter(
        seriesId: String,
        chapterId: String,
        pages: List<Page>,
        format: ChapterFormat,
    ): Result<Unit> {
        val base = resolvePath(seriesId, chapterId)
        val dest = if (format is ChapterFormat.Cbz) base.resolveSibling(base.fileName.toString() + ".cbz") else base
        return try {
            writerSelector.write(pages, dest, format)
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("writeChapter failed: ${e.message}", e))
        }
    }

    private fun readPagesFromFolder(dir: Path): Result<List<Page>> {
        val filenames = Files.list(dir).map { it.fileName.toString() }.toList()
        val ordered = PageFormatUtils.buildPageIndex(filenames)
        val pages = ordered.mapIndexed { i, name ->
            Page(i, Files.readAllBytes(dir.resolve(name)), PageFormatUtils.mimeTypeFor(name))
        }
        return Result.Success(pages)
    }

    private fun readPagesFromCbz(file: Path): Result<List<Page>> {
        val entryMap = mutableMapOf<String, ByteArray>()
        ZipInputStream(Files.newInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryMap[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val ordered = PageFormatUtils.buildPageIndex(entryMap.keys.toList())
        val pages = ordered.mapIndexed { i, name ->
            Page(i, entryMap[name]!!, PageFormatUtils.mimeTypeFor(name))
        }
        return Result.Success(pages)
    }

    private fun readPageFromFolder(dir: Path, index: Int): Result<Page> {
        val filenames = Files.list(dir).map { it.fileName.toString() }.toList()
        val ordered = PageFormatUtils.buildPageIndex(filenames)
        if (index >= ordered.size) return Result.Failure(AppError.NotFound("Page", index.toString()))
        val name = ordered[index]
        return Result.Success(Page(index, Files.readAllBytes(dir.resolve(name)), PageFormatUtils.mimeTypeFor(name)))
    }

    private fun readPageFromCbz(file: Path, index: Int): Result<Page> {
        val entryNames = mutableListOf<String>()
        ZipFile(file.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entryNames.add(it.name) }
        }
        val ordered = PageFormatUtils.buildPageIndex(entryNames)
        if (index >= ordered.size) return Result.Failure(AppError.NotFound("Page", index.toString()))
        val name = ordered[index]
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry(name) ?: return Result.Failure(AppError.NotFound("Page", name))
            return Result.Success(Page(index, zip.getInputStream(entry).readBytes(), PageFormatUtils.mimeTypeFor(name)))
        }
    }
}
