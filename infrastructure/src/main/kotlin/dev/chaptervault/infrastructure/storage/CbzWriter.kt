package dev.chaptervault.infrastructure.storage

import dev.chaptervault.shared.format.ChapterFormat
import java.nio.file.Path

class CbzWriter : ChapterArchiveWriter {
    override val supportedFormat: ChapterFormat = ChapterFormat.Cbz

    override suspend fun write(pages: List<Page>, destination: Path) {
        TODO("CbzWriter not yet implemented")
    }
}
