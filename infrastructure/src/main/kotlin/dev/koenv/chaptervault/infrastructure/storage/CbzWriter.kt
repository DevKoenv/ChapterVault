package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Cbz

    override suspend fun write(pages: List<Page>, destination: Path) {
        Files.createDirectories(destination.parent)
        ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
            for (page in pages) {
                zip.putNextEntry(ZipEntry("%03d.jpg".format(page.index)))
                zip.write(page.data)
                zip.closeEntry()
            }
        }
    }
}
