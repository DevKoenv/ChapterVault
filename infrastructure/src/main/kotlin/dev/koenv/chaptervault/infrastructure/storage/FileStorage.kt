package dev.koenv.chaptervault.infrastructure.storage

import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import java.nio.file.Path

class FileStorage(private val basePath: Path) {
    fun resolvePath(seriesId: String, chapterId: String): Path =
        basePath.resolve(seriesId).resolve(chapterId)

    suspend fun readPages(chapterPath: Path): Result<List<Page>> =
        Result.Failure(AppError.InternalError("FileStorage not yet implemented"))
}
