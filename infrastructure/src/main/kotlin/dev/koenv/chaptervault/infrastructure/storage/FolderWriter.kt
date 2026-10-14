package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.Files
import java.nio.file.Path

class FolderWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Folder

    /** Fills `<destination>.part` first and renames it when complete, so a failure never leaves a half-filled chapter. */
    override suspend fun write(
        pages: List<Page>,
        destination: Path,
    ) {
        Files.createDirectories(destination.parent)
        val part = destination.resolveSibling(destination.fileName.toString() + PART_SUFFIX)
        part.toFile().deleteRecursively()
        try {
            Files.createDirectories(part)
            for (page in pages) {
                Files.write(part.resolve("%03d.%s".format(page.index, PageFormatUtils.extensionFor(page))), page.data)
            }
            destination.toFile().deleteRecursively()
            Files.move(part, destination)
        } catch (e: Throwable) {
            part.toFile().deleteRecursively()
            throw e
        }
    }
}
