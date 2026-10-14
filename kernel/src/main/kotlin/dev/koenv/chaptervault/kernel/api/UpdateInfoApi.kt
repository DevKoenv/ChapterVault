package dev.koenv.chaptervault.kernel.api

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val updateAvailable: Boolean,
)

/** What the server knows about newer releases of itself. */
interface UpdateInfoApi {
    fun getUpdateInfo(): UpdateInfo
}
