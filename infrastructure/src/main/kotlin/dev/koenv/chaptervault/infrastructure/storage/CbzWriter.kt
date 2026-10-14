package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Cbz

    /**
     * Writes to `<destination>.part` and moves it into place once complete, so a crash or full disk never leaves a
     * truncated archive where [FileStorage] would find it, and a failed re-download keeps the previous archive.
     */
    override suspend fun write(
        pages: List<Page>,
        destination: Path,
    ) {
        Files.createDirectories(destination.parent)
        val part = destination.resolveSibling(destination.fileName.toString() + PART_SUFFIX)
        try {
            ZipOutputStream(Files.newOutputStream(part)).use { zip ->
                for (page in pages) {
                    zip.putNextEntry(ZipEntry("%03d.%s".format(page.index, PageFormatUtils.extensionFor(page))))
                    zip.write(page.data)
                    zip.closeEntry()
                }
            }
            moveIntoPlace(part, destination)
        } catch (e: Throwable) {
            Files.deleteIfExists(part)
            throw e
        }
    }

    private fun moveIntoPlace(
        part: Path,
        destination: Path,
    ) {
        try {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal const val PART_SUFFIX = ".part"
