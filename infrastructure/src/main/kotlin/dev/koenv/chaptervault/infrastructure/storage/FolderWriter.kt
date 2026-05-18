package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.Files
import java.nio.file.Path

class FolderWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Folder

    override suspend fun write(pages: List<Page>, destination: Path) {
        Files.createDirectories(destination)
        for (page in pages) {
            Files.write(destination.resolve("%03d.jpg".format(page.index)), page.data)
        }
    }
}
