package dev.koenv.chaptervault.kernel.event

import dev.koenv.chaptervault.kernel.library.Chapter
import dev.koenv.chaptervault.kernel.library.Series

data class NewChaptersDiscovered(
    val series: Series,
    val chapters: List<Chapter>,
) : DomainEvent()
