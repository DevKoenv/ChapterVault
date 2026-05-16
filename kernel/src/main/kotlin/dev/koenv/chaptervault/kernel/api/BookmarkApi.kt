package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import java.time.Instant

data class Bookmark(val id: Id, val chapterId: Id, val page: Int, val createdAt: Instant)

interface BookmarkApi {
    suspend fun create(chapterId: Id, page: Int): Result<Bookmark>
    suspend fun list(seriesId: Id): Result<List<Bookmark>>
    suspend fun delete(bookmarkId: Id): Result<Unit>
}
