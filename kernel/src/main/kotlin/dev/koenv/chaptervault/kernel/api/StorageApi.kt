package dev.koenv.chaptervault.kernel.api

import dev.koenv.chaptervault.shared.result.Result

data class StorageUsage(
    val libraryBytes: Long,
    val thumbnailsBytes: Long,
    /** Free space on the disk that holds the library, as far as this process may use it. */
    val freeBytes: Long,
    val totalBytes: Long,
    /** Downloads stop below this much free space (`storage.minFreeMb`); 0 = no limit. */
    val minFreeBytes: Long,
)

interface StorageApi {
    suspend fun storageUsage(): Result<StorageUsage>
}
