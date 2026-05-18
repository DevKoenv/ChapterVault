package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.shared.format.ChapterFormat
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import java.nio.file.Path

class FileStorage(
    private val basePath: Path,
    private val writerSelector: ArchiveWriterSelector,
) {
    fun resolvePath(seriesId: String, chapterId: String): Path =
        basePath.resolve(seriesId).resolve(chapterId)

    suspend fun readPages(chapterPath: Path): Result<List<Page>> =
        Result.Failure(AppError.InternalError("FileStorage not yet implemented"))

    suspend fun writeChapter(
        seriesId: String,
        chapterId: String,
        pages: List<Page>,
        format: ChapterFormat,
    ): Result<Unit> {
        return try {
            writerSelector.write(pages, resolvePath(seriesId, chapterId), format)
        } catch (e: Exception) {
            Result.Failure(AppError.InternalError("writeChapter failed: ${e.message}", e))
        }
    }
}
