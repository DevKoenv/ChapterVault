package dev.koenv.chaptervault.connectors.impl

import dev.koenv.chaptervault.core.connector.Connector
import dev.koenv.chaptervault.core.connector.ConnectorConfig
import dev.koenv.chaptervault.core.connector.ConnectorFeatures
import dev.koenv.chaptervault.core.domain.ChapterMetadata
import dev.koenv.chaptervault.core.domain.SeriesMetadata
import dev.koenv.chaptervault.core.domain.SeriesSearchResult
import dev.koenv.chaptervault.core.domain.SeriesStatus
import dev.koenv.chaptervault.core.ratelimit.RateLimitConfig
import dev.koenv.chaptervault.core.storage.StorageSink
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Example connector demonstrating authentication flows.
 *
 * This connector shows how to:
 * - Handle username/password login
 * - Manage authentication tokens (JWT, session tokens)
 * - Refresh expired tokens
 * - Store and reuse session cookies
 * - Handle premium/subscriber-only content
 * - Gracefully handle authentication failures
 *
 * Authentication patterns demonstrated:
 * 1. Form-based login (username/password)
 * 2. Token-based API authentication (Bearer tokens)
 * 3. Cookie-based sessions
 * 4. Token refresh flow
 *
 * NOTE: This is a fictional example for "premium-manga.example.com"
 */
class ExampleAuthenticatedConnector(
    private val credentials: Credentials? = null
) : Connector {

    private val logger = LoggerFactory.getLogger(ExampleAuthenticatedConnector::class.java)

    /**
     * Credentials for authentication.
     */
    data class Credentials(
        val username: String,
        val password: String
    )

    /**
     * Authentication state.
     */
    private data class AuthState(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Instant,
        val isPremium: Boolean
    )

    override val config = ConnectorConfig(
        name = "ExampleAuthenticatedConnector",
        version = "1.0.0",
        rateLimitConfig = RateLimitConfig(
            minDelay = 500.seconds,
            maxConcurrent = 3,
            maxRequestsPerWindow = 100,
            windowDuration = 60.seconds
        ),
        features = ConnectorFeatures(
            supportsSearch = true,
            requiresAuth = true,             // This connector requires authentication
            supportsBatchDownload = true,
            supportsPageCount = true,
            maxConcurrentDownloads = 3
        ),
        priority = 20  // Higher priority for premium connector
    )

    override val baseUrls = listOf(
        "https://premium-manga.example.com/*",
        "https://api.premium-manga.example.com/*"
    )

    /**
     * HTTP client with cookie storage and JSON support.
     */
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpCookies) {
            // Automatic cookie handling for session-based auth
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
        defaultRequest {
            header("User-Agent", "ChapterVault/1.0")
            header("Accept", "application/json")
        }
    }

    /**
     * Current authentication state.
     */
    private var authState: AuthState? = null
    private val authMutex = Mutex()

    // ==================== API Response Models ====================

    @Serializable
    private data class LoginResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,  // seconds
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("user") val user: UserInfo? = null
    )

    @Serializable
    private data class UserInfo(
        val id: String,
        val username: String,
        @SerialName("is_premium") val isPremium: Boolean = false,
        @SerialName("subscription_tier") val subscriptionTier: String? = null
    )

    @Serializable
    private data class ApiSearchResponse(
        val results: List<ApiSeriesResult>,
        val total: Int,
        val page: Int,
        @SerialName("per_page") val perPage: Int
    )

    @Serializable
    private data class ApiSeriesResult(
        val id: String,
        val title: String,
        val slug: String,
        val description: String? = null,
        @SerialName("cover_url") val coverUrl: String? = null,
        val author: String? = null,
        val status: String? = null,
        val tags: List<String> = emptyList(),
        @SerialName("is_premium") val isPremium: Boolean = false
    )

    @Serializable
    private data class ApiChapterResponse(
        val chapters: List<ApiChapter>
    )

    @Serializable
    private data class ApiChapter(
        val id: String,
        val title: String,
        @SerialName("chapter_number") val chapterNumber: String,
        @SerialName("publish_date") val publishDate: String? = null,
        @SerialName("page_count") val pageCount: Int? = null,
        @SerialName("is_premium") val isPremium: Boolean = false
    )

    @Serializable
    private data class ApiPagesResponse(
        val pages: List<ApiPage>
    )

    @Serializable
    private data class ApiPage(
        val index: Int,
        val url: String,
        val width: Int? = null,
        val height: Int? = null
    )

    @Serializable
    private data class ApiError(
        val error: String,
        val message: String,
        val code: String? = null
    )

    // ==================== Authentication Methods ====================

    /**
     * Ensure we have a valid authentication token.
     * Performs login or token refresh as needed.
     */
    private suspend fun ensureAuthenticated(): AuthState {
        return authMutex.withLock {
            val current = authState

            // Check if we have a valid token
            if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(60))) {
                return@withLock current
            }

            // Try to refresh if we have a refresh token
            if (current?.refreshToken != null) {
                try {
                    val refreshed = refreshToken(current.refreshToken)
                    authState = refreshed
                    return@withLock refreshed
                } catch (e: Exception) {
                    logger.warn("Token refresh failed, will re-login: {}", e.message)
                }
            }

            // Perform fresh login
            val newState = login()
            authState = newState
            newState
        }
    }

    /**
     * Perform login with username/password.
     */
    private suspend fun login(): AuthState {
        val creds = credentials
            ?: throw IllegalStateException("Authentication required but no credentials provided")

        logger.info("Logging in as: {}", creds.username)

        val response = httpClient.submitForm(
            url = "https://api.premium-manga.example.com/auth/login",
            formParameters = parameters {
                append("username", creds.username)
                append("password", creds.password)
                append("grant_type", "password")
            }
        )

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Login failed: {} - {}", response.status, errorBody)
            throw IllegalStateException("Login failed: ${response.status}")
        }

        val loginResponse = Json.decodeFromString<LoginResponse>(response.bodyAsText())

        logger.info("Login successful. Premium: {}", loginResponse.user?.isPremium ?: false)

        return AuthState(
            accessToken = loginResponse.accessToken,
            refreshToken = loginResponse.refreshToken,
            expiresAt = Instant.now().plusSeconds(loginResponse.expiresIn),
            isPremium = loginResponse.user?.isPremium ?: false
        )
    }

    /**
     * Refresh an expired access token.
     */
    private suspend fun refreshToken(refreshToken: String): AuthState {
        logger.info("Refreshing access token...")

        val response = httpClient.submitForm(
            url = "https://api.premium-manga.example.com/auth/refresh",
            formParameters = parameters {
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
            }
        )

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Token refresh failed: ${response.status}")
        }

        val loginResponse = Json.decodeFromString<LoginResponse>(response.bodyAsText())

        logger.info("Token refreshed successfully")

        return AuthState(
            accessToken = loginResponse.accessToken,
            refreshToken = loginResponse.refreshToken ?: refreshToken,
            expiresAt = Instant.now().plusSeconds(loginResponse.expiresIn),
            isPremium = authState?.isPremium ?: false
        )
    }

    /**
     * Make an authenticated API request.
     */
    private suspend inline fun <reified T> authenticatedGet(url: String): T {
        val auth = ensureAuthenticated()

        val response = httpClient.get(url) {
            header("Authorization", "Bearer ${auth.accessToken}")
        }

        if (response.status == HttpStatusCode.Unauthorized) {
            // Token might have been invalidated server-side, clear and retry
            authMutex.withLock { authState = null }
            val newAuth = ensureAuthenticated()

            val retryResponse = httpClient.get(url) {
                header("Authorization", "Bearer ${newAuth.accessToken}")
            }

            if (!retryResponse.status.isSuccess()) {
                throw IllegalStateException("API request failed: ${retryResponse.status}")
            }

            return Json.decodeFromString(retryResponse.bodyAsText())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException("API request failed: ${response.status} - $errorBody")
        }

        return Json.decodeFromString(response.bodyAsText())
    }

    // ==================== Connector Interface Implementation ====================

    override suspend fun searchSeries(query: String): List<SeriesSearchResult> {
        logger.info("Searching for: {}", query)

        val searchResponse = authenticatedGet<ApiSearchResponse>(
            "https://api.premium-manga.example.com/v1/search?q=${query.encodeURLParameter()}&limit=50"
        )

        return searchResponse.results.map { result ->
            SeriesSearchResult(
                url = "https://premium-manga.example.com/series/${result.slug}",
                title = buildTitle(result.title, result.isPremium),
                description = result.description,
                coverUrl = result.coverUrl
            )
        }
    }

    override suspend fun fetchSeriesMetadata(seriesUrl: String): SeriesMetadata {
        logger.info("Fetching series: {}", seriesUrl)

        val slug = extractSlug(seriesUrl)
        val series = authenticatedGet<ApiSeriesResult>(
            "https://api.premium-manga.example.com/v1/series/$slug"
        )

        val auth = ensureAuthenticated()
        val canAccess = !series.isPremium || auth.isPremium

        return SeriesMetadata(
            url = seriesUrl,
            title = buildTitle(series.title, series.isPremium),
            description = buildDescription(series.description, series.isPremium, canAccess),
            author = series.author,
            coverUrl = series.coverUrl,
            tags = series.tags + if (series.isPremium) listOf("Premium") else emptyList(),
            status = parseStatus(series.status)
        )
    }

    override suspend fun fetchChapterList(seriesUrl: String): List<ChapterMetadata> {
        logger.info("Fetching chapters: {}", seriesUrl)

        val slug = extractSlug(seriesUrl)
        val chaptersResponse = authenticatedGet<ApiChapterResponse>(
            "https://api.premium-manga.example.com/v1/series/$slug/chapters"
        )

        val auth = ensureAuthenticated()

        return chaptersResponse.chapters.map { chapter ->
            val canAccess = !chapter.isPremium || auth.isPremium

            ChapterMetadata(
                url = "https://premium-manga.example.com/series/$slug/chapter/${chapter.id}",
                seriesUrl = seriesUrl,
                title = buildChapterTitle(chapter.title, chapter.isPremium, canAccess),
                chapterNumber = chapter.chapterNumber,
                publishDate = chapter.publishDate,
                pageCount = chapter.pageCount
            )
        }.sortedBy { it.chapterNumber.toDoubleOrNull() ?: 0.0 }
    }

    override suspend fun downloadChapter(chapterUrl: String, storage: StorageSink) {
        logger.info("Downloading chapter: {}", chapterUrl)

        // Extract series slug and chapter ID from URL
        val urlParts = Regex("""/series/([^/]+)/chapter/([^/]+)""").find(chapterUrl)
            ?: throw IllegalArgumentException("Invalid chapter URL: $chapterUrl")

        val seriesSlug = urlParts.groupValues[1]
        val chapterId = urlParts.groupValues[2]

        // Fetch page URLs (this endpoint typically requires premium for premium chapters)
        val pagesResponse = try {
            authenticatedGet<ApiPagesResponse>(
                "https://api.premium-manga.example.com/v1/series/$seriesSlug/chapters/$chapterId/pages"
            )
        } catch (e: Exception) {
            if ("premium" in e.message?.lowercase().orEmpty() || "subscription" in e.message?.lowercase().orEmpty()) {
                throw IllegalStateException("This chapter requires a premium subscription to download")
            }
            throw e
        }

        if (pagesResponse.pages.isEmpty()) {
            throw IllegalStateException("No pages found for chapter")
        }

        logger.info("Downloading {} pages", pagesResponse.pages.size)

        val auth = ensureAuthenticated()

        pagesResponse.pages.sortedBy { it.index }.forEachIndexed { index, page ->
            logger.debug("Downloading page {}/{}", index + 1, pagesResponse.pages.size)

            // Download image with authentication
            val response = httpClient.get(page.url) {
                header("Authorization", "Bearer ${auth.accessToken}")
                header("Referer", chapterUrl)
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Failed to download page ${index + 1}: ${response.status}")
            }

            val imageBytes = response.readRawBytes()
            val mimeType = guessMimeType(page.url)

            storage.writePage(index, imageBytes, mimeType)
        }

        logger.info("Chapter download complete")
    }

    // ==================== Helper Methods ====================

    /**
     * Extract series slug from URL.
     */
    private fun extractSlug(url: String): String {
        return Regex("""/series/([^/]+)""").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid series URL: $url")
    }

    /**
     * Build title with premium indicator.
     */
    private fun buildTitle(title: String, isPremium: Boolean): String {
        return if (isPremium) "[$] $title" else title
    }

    /**
     * Build description with access information.
     */
    private fun buildDescription(description: String?, isPremium: Boolean, canAccess: Boolean): String? {
        if (description == null) return null

        return when {
            isPremium && !canAccess -> "[Premium - Subscription Required]\n\n$description"
            isPremium && canAccess -> "[Premium]\n\n$description"
            else -> description
        }
    }

    /**
     * Build chapter title with access indicator.
     */
    private fun buildChapterTitle(title: String, isPremium: Boolean, canAccess: Boolean): String {
        return when {
            isPremium && !canAccess -> "[$] $title (Premium Required)"
            isPremium -> "[$] $title"
            else -> title
        }
    }

    /**
     * Parse status string to enum.
     */
    private fun parseStatus(status: String?): SeriesStatus {
        return when (status?.lowercase()) {
            "ongoing", "releasing" -> SeriesStatus.ONGOING
            "completed", "finished" -> SeriesStatus.COMPLETED
            "hiatus", "on_hold" -> SeriesStatus.HIATUS
            "cancelled", "dropped" -> SeriesStatus.CANCELLED
            else -> SeriesStatus.UNKNOWN
        }
    }

    /**
     * Guess MIME type from URL.
     */
    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            ".png" in lower -> "image/png"
            ".gif" in lower -> "image/gif"
            ".webp" in lower -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * Check if current session has premium access.
     */
    suspend fun isPremiumUser(): Boolean {
        return try {
            ensureAuthenticated().isPremium
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Logout and clear authentication state.
     */
    suspend fun logout() {
        authMutex.withLock {
            authState?.let { state ->
                try {
                    httpClient.post("https://api.premium-manga.example.com/auth/logout") {
                        header("Authorization", "Bearer ${state.accessToken}")
                    }
                } catch (e: Exception) {
                    logger.debug("Logout request failed: {}", e.message)
                }
            }
            authState = null
        }
        logger.info("Logged out")
    }

    /**
     * Close the HTTP client.
     */
    fun close() {
        httpClient.close()
    }
}
