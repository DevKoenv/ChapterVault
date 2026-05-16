package dev.chaptervault.infrastructure.storage

import dev.chaptervault.shared.result.AppError
import dev.chaptervault.shared.result.Result
import java.nio.file.Path

class FileStorage(private val basePath: Path) {
    fun resolvePath(seriesId: String, chapterId: String): Path =
        basePath.resolve(seriesId).resolve(chapterId)

    suspend fun readPages(chapterPath: Path): Result<List<Page>> =
        Result.Failure(AppError.InternalError("FileStorage not yet implemented"))
}
