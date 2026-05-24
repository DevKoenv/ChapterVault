package dev.koenv.chaptervault.server

import dev.koenv.chaptervault.kernel.api.LibraryCommandApi
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.api.ProgressApi
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.extension.ExtensionContext

data class DefaultExtensionContext(
    override val libraryRead: LibraryReadApi,
    override val libraryCommand: LibraryCommandApi,
    override val progress: ProgressApi,
    override val system: SystemApi,
    override val eventBus: EventBus,
) : ExtensionContext
