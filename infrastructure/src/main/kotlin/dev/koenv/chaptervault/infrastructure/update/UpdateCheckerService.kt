package dev.koenv.chaptervault.infrastructure.update

import dev.koenv.chaptervault.kernel.api.UpdateInfo
import dev.koenv.chaptervault.kernel.api.UpdateInfoApi
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

private const val DEFAULT_RECHECK_MS = 24L * 60 * 60 * 1000

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
)

/** A version split into its numbers and whether it is a pre-release such as `1.0.0-rc1`. */
private data class Version(
    val numbers: List<Int>,
    val preRelease: Boolean,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        // the same numbers: 1.0.0-rc1 comes before 1.0.0
        return other.preRelease.compareTo(preRelease)
    }

    companion object {
        fun parse(text: String): Version {
            val core = text.removePrefix("v")
            return Version(core.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }, '-' in core)
        }
    }
}

class UpdateCheckerService(
    private val httpClient: HttpClient,
    private val currentVersion: String,
    private val repoOwner: String = "DevKoenv",
    private val repoName: String = "ChapterVault",
) : UpdateInfoApi {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile
    private var latestRelease: GitHubRelease? = null

    /** Checks now and then again every [recheckEveryMs], so a server that stays up for months still learns of releases. */
    fun startAsync(recheckEveryMs: Long = DEFAULT_RECHECK_MS) {
        job?.cancel()
        job =
            scope.launch {
                while (isActive) {
                    checkNow()
                    delay(recheckEveryMs)
                }
            }
    }

    fun stop() {
        scope.cancel()
    }

    override fun getUpdateInfo(): UpdateInfo {
        val release = latestRelease
        val latestVersion = release?.tagName?.removePrefix("v")
        val updateAvailable = latestVersion != null && Version.parse(latestVersion) > Version.parse(currentVersion)
        return UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            releaseUrl = release?.htmlUrl,
            updateAvailable = updateAvailable,
        )
    }

    /** One check. A failure of any kind (offline, rate limited, odd answer) keeps what was known before. */
    internal suspend fun checkNow() {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val response = httpClient.get(url)
            if (response.status.isSuccess()) {
                val release = response.body<GitHubRelease>()
                latestRelease = release
                val latestVersion = release.tagName.removePrefix("v")
                if (Version.parse(latestVersion) > Version.parse(currentVersion)) {
                    logger.warn {
                        "A new version of ChapterVault is available: $latestVersion (current: $currentVersion) \u2014 ${release.htmlUrl}"
                    }
                } else {
                    logger.info { "ChapterVault is up to date (version $currentVersion)" }
                }
            } else {
                logger.debug { "Update check returned status ${response.status}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "Update check failed" }
        }
    }
}
