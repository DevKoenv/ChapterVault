package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Page
import dev.koenv.chaptervault.shared.result.Result

interface ChapterPageSource {
    suspend fun readPage(chapter: Chapter, index: Int): Result<Page>
    suspend fun countPages(chapter: Chapter): Result<Int>
}
