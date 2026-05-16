package dev.chaptervault.infrastructure.storage

import dev.chaptervault.shared.format.ChapterFormat
import java.nio.file.Path

class FolderWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Folder

    override suspend fun write(pages: List<Page>, destination: Path) {
        TODO("FolderWriter not yet implemented")
    }
}
