package dev.chaptervault.infrastructure.storage

import dev.chaptervault.shared.format.ChapterFormat
import java.nio.file.Path

data class Page(val index: Int, val data: ByteArray)

interface ChapterArchiveWriter {
    val supportedFormat: ChapterFormat
    suspend fun write(pages: List<Page>, destination: Path)
}
