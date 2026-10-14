package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.api.ChapterPageSource
import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// one facade over everything on disk (chapters, covers, housekeeping), which is why it has many small functions
@Suppress("TooManyFunctions")
open class FileStorage(
    private val libraryPath: Path,
    private val thumbnailsPath: Path,
    private val writerSelector: ArchiveWriterSelector,
    private val thumbnailFormat: ThumbnailFormat = JpegThumbnailFormat,
) : ChapterPageSource {
    private val logger = KotlinLogging.logger {}

    fun ensureDirectories() {
        Files.createDirectories(libraryPath)
        Files.createDirectories(thumbnailsPath)
    }

    fun writeCover(
        seriesId: String,
        bytes: ByteArray,
    ) {
        Files.createDirectories(thumbnailsPath)
        val encoded = thumbnailFormat.encode(bytes)
        Files.write(thumbnailsPath.resolve("$seriesId.${thumbnailFormat.extension}"), encoded)
    }

    fun readCover(seriesId: String): Result<Pair<ByteArray, String>> {
        val file = thumbnailsPath.resolve("$seriesId.${thumbnailFormat.extension}")
        if (!Files.isRegularFile(file)) return Result.Failure(AppError.NotFound("Cover", seriesId))
        val bytes = Files.readAllBytes(file)
        return Result.Success(bytes to (PageFormatUtils.sniffMimeType(bytes) ?: thumbnailFormat.mimeType))
    }

    fun chapterExists(chapter: Chapter): Boolean {
        val path = chapterPath(chapter)
        return Files.isRegularFile(path) || Files.isDirectory(path)
    }

    // streams chapter to out without buffering full archive in memory
    // folder chapters are zipped on-the-fly, throws if files not found
    fun streamChapterTo(
        chapter: Chapter,
        out: OutputStream,
    ) {
        val path = chapterPath(chapter)
        when {
            Files.isRegularFile(path) -> Files.newInputStream(path).use { it.copyTo(out) }
            Files.isDirectory(path) -> zipFolderTo(path, out)
            else -> throw java.io.FileNotFoundException("Chapter files not found: ${chapter.id}")
        }
    }

    private fun zipFolderTo(
        folder: Path,
        out: OutputStream,
    ) {
        ZipOutputStream(out).use { zip ->
            val files = Files.list(folder).use { stream -> stream.sorted().toList() }
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.fileName.toString()))
                Files.newInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun resolvePath(
        seriesId: String,
        chapterId: String,
    ): Path = libraryPath.resolve(seriesId).resolve(chapterId)

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

    override suspend fun readPage(
        chapter: Chapter,
        index: Int,
    ): Result<Page> {
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
            Files.isRegularFile(path) -> Result.Success(cbzPageIndex(path).size)
            else -> Result.Failure(AppError.NotFound("Chapter files", chapter.id.toString()))
        }
    }

    fun deleteChapterFiles(
        seriesId: String,
        chapterId: String,
    ) {
        val base = resolvePath(seriesId, chapterId)
        val cbz = base.resolveSibling(base.fileName.toString() + ".cbz")
        try {
            Files.deleteIfExists(cbz)
            if (Files.isDirectory(base)) {
                base.toFile().deleteRecursively()
            } else {
                Files.deleteIfExists(base)
            }
        } catch (e: IOException) {
            logger.warn { "Failed to delete files for chapter $chapterId: ${e.message}" }
        }
    }

    /**
     * Moves series directories that have no database row to `.trash/` (nothing is deleted). Only directories named
     * like a series id are considered: anything else in the library path belongs to somebody else and is left alone.
     */
    fun cleanupOrphanedDirs(knownSeriesIds: Set<String>) {
        if (!Files.isDirectory(libraryPath)) return
        val orphans =
            Files.list(libraryPath).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { isSeriesId(it.fileName.toString()) }
                    .filter { it.fileName.toString() !in knownSeriesIds }
                    .toList()
            }
        if (orphans.isEmpty()) return
        val trash = Files.createDirectories(libraryPath.resolve(TRASH_DIR))
        for (dir in orphans) {
            val target = uniqueTarget(trash, "${dir.fileName}-${System.currentTimeMillis()}")
            logger.warn { "Series ${dir.fileName} has no database entry; moving its files to $target" }
            try {
                Files.move(dir, target)
            } catch (e: IOException) {
                logger.warn { "Could not move orphaned library files ${dir.fileName}: ${e.message}" }
            }
        }
    }

    /** Removes `*.part` leftovers of downloads that were interrupted by a crash. */
    fun sweepPartialWrites() {
        if (!Files.isDirectory(libraryPath)) return
        val seriesDirs =
            Files.list(libraryPath).use { stream ->
                stream.filter { Files.isDirectory(it) && isSeriesId(it.fileName.toString()) }.toList()
            }
        for (seriesDir in seriesDirs) {
            val parts =
                Files.list(seriesDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(PART_SUFFIX) }.toList()
                }
            for (part in parts) {
                logger.info { "Removing partial download ${part.fileName}" }
                part.toFile().deleteRecursively()
            }
        }
    }

    fun cleanupOrphanedThumbnails(knownSeriesIds: Set<String>) {
        if (!Files.isDirectory(thumbnailsPath)) return
        val suffix = ".${thumbnailFormat.extension}"
        Files.list(thumbnailsPath).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(suffix) }
                .filter { isSeriesId(it.fileName.toString().removeSuffix(suffix)) }
                .filter { it.fileName.toString().removeSuffix(suffix) !in knownSeriesIds }
                .forEach { file ->
                    logger.info { "Removing orphaned thumbnail for series ${file.fileName}" }
                    Files.deleteIfExists(file)
                }
        }
    }

    private fun isSeriesId(name: String): Boolean = runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false)

    private fun uniqueTarget(
        dir: Path,
        name: String,
    ): Path {
        var candidate = dir.resolve(name)
        var n = 1
        while (Files.exists(candidate)) candidate = dir.resolve("$name-${n++}")
        return candidate
    }

    open fun deleteSeriesFiles(seriesId: String) {
        val dir = libraryPath.resolve(seriesId)
        try {
            if (Files.exists(dir)) dir.toFile().deleteRecursively()
            Files.deleteIfExists(thumbnailsPath.resolve("$seriesId.${thumbnailFormat.extension}"))
        } catch (e: IOException) {
            logger.warn { "Failed to delete library files for series $seriesId: ${e.message}" }
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
        val filenames = listNames(dir)
        val ordered = PageFormatUtils.buildPageIndex(filenames)
        val pages =
            ordered.mapIndexed { i, name ->
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
        val pages =
            ordered.mapIndexed { i, name ->
                Page(i, entryMap[name]!!, PageFormatUtils.mimeTypeFor(name))
            }
        return Result.Success(pages)
    }

    private fun readPageFromFolder(
        dir: Path,
        index: Int,
    ): Result<Page> {
        val filenames = listNames(dir)
        val ordered = PageFormatUtils.buildPageIndex(filenames)
        if (index < 0 || index >= ordered.size) return Result.Failure(AppError.NotFound("Page", index.toString()))
        val name = ordered[index]
        return Result.Success(Page(index, Files.readAllBytes(dir.resolve(name)), PageFormatUtils.mimeTypeFor(name)))
    }

    private fun readPageFromCbz(
        file: Path,
        index: Int,
    ): Result<Page> {
        val ordered = cbzPageIndex(file)
        if (index < 0 || index >= ordered.size) return Result.Failure(AppError.NotFound("Page", index.toString()))
        val name = ordered[index]
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry(name) ?: return Result.Failure(AppError.NotFound("Page", name))
            return Result.Success(Page(index, zip.getInputStream(entry).readBytes(), PageFormatUtils.mimeTypeFor(name)))
        }
    }

    private fun listNames(dir: Path): List<String> = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }

    private class CachedIndex(
        val lastModified: Long,
        val size: Long,
        val names: List<String>,
    )

    // sorted page names per archive; a re-downloaded chapter changes size or mtime, which invalidates the entry
    private val indexCache =
        object : LinkedHashMap<Path, CachedIndex>(INDEX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, CachedIndex>?) = size > INDEX_CACHE_SIZE
        }

    private fun cbzPageIndex(file: Path): List<String> {
        val lastModified = Files.getLastModifiedTime(file).toMillis()
        val size = Files.size(file)
        synchronized(indexCache) { indexCache[file]?.let { if (it.lastModified == lastModified && it.size == size) return it.names } }
        val names =
            ZipFile(file.toFile()).use { zip ->
                PageFormatUtils.buildPageIndex(
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList(),
                )
            }
        synchronized(indexCache) { indexCache[file] = CachedIndex(lastModified, size, names) }
        return names
    }

    private companion object {
        const val TRASH_DIR = ".trash"
        const val INDEX_CACHE_SIZE = 64
    }
}
