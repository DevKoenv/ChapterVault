package dev.koenv.chaptervault.infrastructure.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
)

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val updateAvailable: Boolean,
)

class UpdateCheckerService(
    private val httpClient: HttpClient,
    private val currentVersion: String,
    private val repoOwner: String = "DevKoenv",
    private val repoName: String = "ChapterVault",
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var latestRelease: GitHubRelease? = null

    fun startAsync() {
        scope.launch { checkForUpdate() }
    }

    fun getUpdateInfo(): UpdateInfo {
        val release = latestRelease
        val latestVersion = release?.tagName?.removePrefix("v")
        val updateAvailable = latestVersion != null && isNewerVersion(latestVersion, currentVersion)
        return UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            releaseUrl = release?.htmlUrl,
            updateAvailable = updateAvailable,
        )
    }

    private suspend fun checkForUpdate() {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val response = httpClient.get(url)
            if (response.status.isSuccess()) {
                val release = response.body<GitHubRelease>()
                latestRelease = release
                val latestVersion = release.tagName.removePrefix("v")
                if (isNewerVersion(latestVersion, currentVersion)) {
                    logger.warn {
                        "A new version of ChapterVault is available: $latestVersion (current: $currentVersion) — ${release.htmlUrl}"
                    }
                } else {
                    logger.info { "ChapterVault is up to date (version $currentVersion)" }
                }
            } else {
                logger.debug { "Update check returned status ${response.status}" }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Update check failed" }
        }
    }

    private fun isNewerVersion(
        candidate: String,
        current: String,
    ): Boolean {
        val candidateParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(candidateParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val c = candidateParts.getOrElse(i) { 0 }
            val cur = currentParts.getOrElse(i) { 0 }
            if (c > cur) return true
            if (c < cur) return false
        }
        return false
    }
}
