package dev.koenv.chaptervault.kernel.extension

import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.event.EventBus

interface ExtensionContext {
    val libraryRead: LibraryReadApi
    val libraryCommand: LibraryCommandApi
    val progress: ProgressApi
    val system: SystemApi
    val eventBus: EventBus
}
