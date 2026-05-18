package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.database.entities.BookmarkTable
import dev.koenv.chaptervault.infrastructure.database.entities.ChapterTable
import dev.koenv.chaptervault.kernel.api.Bookmark
import dev.koenv.chaptervault.kernel.api.BookmarkApi
import dev.koenv.chaptervault.shared.result.AppError
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class BookmarkRepository : BookmarkApi {

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun create(userId: Id, chapterId: Id, page: Int): Result<Bookmark> = dbQuery {
        val id = Id.generate()
        val createdAt = Instant.now()
        BookmarkTable.insert {
            it[BookmarkTable.id] = id.toString()
            it[BookmarkTable.userId] = userId.toString()
            it[BookmarkTable.chapterId] = chapterId.toString()
            it[BookmarkTable.page] = page
            it[BookmarkTable.createdAt] = createdAt.toKotlinInstant()
        }
        Result.Success(Bookmark(id = id, chapterId = chapterId, page = page, createdAt = createdAt))
    }

    override suspend fun list(userId: Id, seriesId: Id): Result<List<Bookmark>> = dbQuery {
        val rows = BookmarkTable
            .join(ChapterTable, JoinType.INNER, BookmarkTable.chapterId, ChapterTable.id)
            .selectAll()
            .where {
                (BookmarkTable.userId eq userId.toString()) and
                    (ChapterTable.seriesId eq seriesId.toString())
            }
            .orderBy(ChapterTable.chapterIndex to SortOrder.ASC, BookmarkTable.page to SortOrder.ASC)
            .map { it.toBookmark() }
        Result.Success(rows)
    }

    override suspend fun delete(userId: Id, bookmarkId: Id): Result<Unit> = dbQuery {
        val deleted = BookmarkTable.deleteWhere {
            (BookmarkTable.id eq bookmarkId.toString()) and
                (BookmarkTable.userId eq userId.toString())
        }
        if (deleted == 0) {
            Result.Failure(AppError.NotFound("bookmark", bookmarkId.toString()))
        } else {
            Result.Success(Unit)
        }
    }

    private fun ResultRow.toBookmark() = Bookmark(
        id = Id.from(this[BookmarkTable.id]),
        chapterId = Id.from(this[BookmarkTable.chapterId]),
        page = this[BookmarkTable.page],
        createdAt = this[BookmarkTable.createdAt].toJavaInstant(),
    )
}
