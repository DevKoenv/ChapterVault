package dev.koenv.chaptervault.extensions.opds

import dev.koenv.chaptervault.extensions.shared.ExtensionBase
import dev.koenv.chaptervault.kernel.api.LibraryReadApi
import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.shared.paging.PageRequest

class OpdsExtension : ExtensionBase() {
    override val id: String = "opds"
    override val name: String = "OPDS Feed"
    override val version: String = "1.0.0"

    private var libraryRead: LibraryReadApi? = null

    override suspend fun onLoad(context: ExtensionContext) {
        libraryRead = context.libraryRead
    }

    override val capabilities: Set<Capability> = setOf(Capability.CanServeOpds)

    suspend fun buildFeed(request: PageRequest): OpdsFeed {
        TODO("OpdsExtension not yet implemented")
    }

    fun buildEntry(series: dev.koenv.chaptervault.kernel.library.Series): OpdsEntry {
        TODO("OpdsExtension not yet implemented")
    }
}
