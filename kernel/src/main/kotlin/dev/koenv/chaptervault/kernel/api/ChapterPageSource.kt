package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.result.Result

interface ChapterPageSource {
    suspend fun readPage(
        chapter: Chapter,
        index: Int,
    ): Result<Page>

    suspend fun countPages(chapter: Chapter): Result<Int>

    /** The MIME type of the first page; OPDS page streaming needs it to describe the pages. */
    suspend fun firstPageMimeType(chapter: Chapter): Result<String> =
        when (val page = readPage(chapter, 0)) {
            is Result.Success -> Result.Success(page.value.mimeType)
            is Result.Failure -> page
        }
}
