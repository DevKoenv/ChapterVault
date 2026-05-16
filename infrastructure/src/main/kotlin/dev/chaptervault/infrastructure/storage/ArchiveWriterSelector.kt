package dev.chaptervault.infrastructure.storage

import dev.chaptervault.shared.format.ChapterFormat
import dev.chaptervault.shared.result.AppError
import dev.chaptervault.shared.result.Result
import java.nio.file.Path

class ArchiveWriterSelector(private val writers: List<ChapterArchiveWriter>) {
    suspend fun write(pages: List<Page>, destination: Path, format: ChapterFormat): Result<Unit> {
        val writer = writers.find { it.supportedFormat == format }
            ?: return Result.Failure(AppError.InternalError("No writer registered for format: $format"))
        return try {
            writer.write(pages, destination)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("Write failed: ${e.message}", e))
        }
    }
}
