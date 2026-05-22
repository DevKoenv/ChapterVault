package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

open class FileStorage(
    private val basePath: Path,
    private val writerSelector: ArchiveWriterSelector,
) : ChapterPageSource {

    private val logger = LoggerFactory.getLogger(FileStorage::class.java)

    fun resolvePath(seriesId: String, chapterId: String): Path =
        basePath.resolve(seriesId).resolve(chapterId)

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

    open fun deleteSeriesFiles(seriesId: String) {
        val dir = basePath.resolve(seriesId)
        try {
            if (Files.exists(dir)) {
                dir.toFile().deleteRecursively()
            }
        } catch (e: IOException) {
            logger.warn("Failed to delete files for series $seriesId: ${e.message}")
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
