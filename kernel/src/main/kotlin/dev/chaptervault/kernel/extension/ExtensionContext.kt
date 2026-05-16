package dev.chaptervault.kernel.extension

import dev.chaptervault.kernel.api.LibraryReadApi
import dev.chaptervault.kernel.api.LibraryCommandApi
import dev.chaptervault.kernel.api.ProgressApi
import dev.chaptervault.kernel.api.SystemApi
import dev.chaptervault.kernel.event.EventBus

interface ExtensionContext {
    val libraryRead: LibraryReadApi
    val libraryCommand: LibraryCommandApi
    val progress: ProgressApi
    val system: SystemApi
    val eventBus: EventBus
}
