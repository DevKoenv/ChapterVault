package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.Path

class FolderWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Folder

    override suspend fun write(pages: List<Page>, destination: Path) {
        TODO("FolderWriter not yet implemented")
    }
}
