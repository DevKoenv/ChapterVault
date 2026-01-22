package dev.koenv.chaptervault.orchestration.task

/**
 * Base class for all orchestration tasks
 */
sealed class Task {
    abstract val id: String
    abstract val url: String
}

/**
 * Task to search for series
 */
data class SearchTask(
    override val id: String,
    val query: String
) : Task() {
    override val url: String = ""  // Search doesn't have a URL
}

/**
 * Task to browse series metadata
 */
data class BrowseSeriesTask(
    override val id: String,
    override val url: String
) : Task()

/**
 * Task to browse chapter list
 */
data class BrowseChaptersTask(
    override val id: String,
    override val url: String
) : Task()

/**
 * Task to download a single chapter
 */
data class DownloadChapterTask(
    override val id: String,
    override val url: String
) : Task()

/**
 * Task to download an entire series (all chapters)
 */
data class DownloadSeriesTask(
    override val id: String,
    override val url: String
) : Task()
