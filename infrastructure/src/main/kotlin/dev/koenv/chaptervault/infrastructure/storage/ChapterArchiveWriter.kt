package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.format.ChapterFormat
import java.nio.file.Path

interface ChapterArchiveWriter {
    val supportedFormat: ChapterFormat
    suspend fun write(pages: List<Page>, destination: Path)
}
