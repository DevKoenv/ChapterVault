package dev.chaptervault.extensions.opds

import dev.chaptervault.kernel.library.Series
import dev.chaptervault.shared.paging.PageRequest

class FeedBuilder {
    fun buildFeed(series: List<Series>, request: PageRequest, totalItems: Long): OpdsFeed {
        TODO("FeedBuilder not yet implemented")
    }

    fun buildEntry(series: Series): OpdsEntry {
        TODO("FeedBuilder not yet implemented")
    }
}
