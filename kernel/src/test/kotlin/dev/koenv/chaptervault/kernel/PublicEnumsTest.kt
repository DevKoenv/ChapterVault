package dev.koenv.chaptervault.kernel

import dev.koenv.chaptervault.kernel.extension.ExtensionSource
import dev.koenv.chaptervault.kernel.library.SeriesStatus
import dev.koenv.chaptervault.kernel.runtime.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These enums appear in REST responses and events, so a value is a promise to clients. Every value must be one the
 * server actually produces; adding one later is compatible, removing one is not.
 */
class PublicEnumsTest {
    @Test
    fun `task types are the ones the server enqueues`() {
        assertEquals(
            setOf("FETCH_SERIES_METADATA", "FETCH_CHAPTERS", "DOWNLOAD_CHAPTER"),
            TaskType.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a series is either in the library or gone`() {
        assertEquals(listOf("IN_LIBRARY"), SeriesStatus.entries.map { it.name })
    }

    @Test
    fun `extensions come from the server or from the data folder`() {
        assertEquals(setOf("BUNDLED", "LOCAL"), ExtensionSource.entries.map { it.name }.toSet())
    }
}
