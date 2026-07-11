package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id

data class BackupProgress(
    val connectorId: String,
    val externalId: String,
    val latestChapterExternalId: String?,
    val latestPage: Int,
    val readAt: String,
)

data class BackupReadingStatus(
    val connectorId: String,
    val externalId: String,
    val status: String,
)

data class BackupOverride(
    val connectorId: String,
    val externalId: String,
    val field: String,
    val value: String,
)

data class BackupBookmark(
    val chapterSeriesConnectorId: String,
    val chapterSeriesExternalId: String,
    val chapterExternalId: String,
    val page: Int,
    val createdAt: String,
)

data class LibraryBackup(
    val version: Int = 1,
    val exportedAt: String,
    val progress: List<BackupProgress>,
    val readingStatuses: List<BackupReadingStatus>,
    val overrides: List<BackupOverride>,
    val bookmarks: List<BackupBookmark>,
)

interface BackupApi {
    suspend fun exportBackup(
        userId: Id,
        exportedAt: String,
    ): Result<LibraryBackup>

    suspend fun importBackup(
        userId: Id,
        backup: LibraryBackup,
    ): Result<BackupImportSummary>
}

data class BackupImportSummary(
    val progressRestored: Int,
    val progressSkipped: Int,
    val statusesRestored: Int,
    val statusesSkipped: Int,
    val overridesRestored: Int,
    val overridesSkipped: Int,
    val bookmarksRestored: Int,
    val bookmarksSkipped: Int,
)
