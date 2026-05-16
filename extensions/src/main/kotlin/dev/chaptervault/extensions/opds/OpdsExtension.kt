package dev.chaptervault.extensions.opds

import dev.chaptervault.extensions.shared.ExtensionBase
import dev.chaptervault.kernel.api.LibraryReadApi
import dev.chaptervault.kernel.extension.Capability
import dev.chaptervault.kernel.extension.ExtensionContext
import dev.chaptervault.shared.paging.PageRequest

class OpdsExtension : ExtensionBase() {
    override val id: String = "opds"
    override val name: String = "OPDS Feed"
    override val version: String = "1.0.0"

    private var libraryRead: LibraryReadApi? = null

    override suspend fun onLoad(context: ExtensionContext) {
        libraryRead = context.libraryRead
    }

    val capability: Capability = Capability.CanServeOpds

    suspend fun buildFeed(request: PageRequest): OpdsFeed {
        TODO("OpdsExtension not yet implemented")
    }

    fun buildEntry(series: dev.chaptervault.kernel.library.Series): OpdsEntry {
        TODO("OpdsExtension not yet implemented")
    }
}
