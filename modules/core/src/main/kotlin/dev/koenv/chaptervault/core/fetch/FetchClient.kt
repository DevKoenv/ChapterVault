package dev.koenv.chaptervault.core.fetch

import java.io.Closeable

/**
 * Unified fetch client interface for HTTP operations.
 *
 * This abstraction provides:
 * - Consistent HTTP interface for connectors
 * - Built-in rate limiting integration
 * - Automatic retries with backoff
 * - Cookie and session management
 * - Future support for instruction-based execution
 *
 * Connectors should use this instead of creating their own HTTP clients.
 */
interface FetchClient : Closeable {

    /**
     * Perform a GET request.
     *
     * @param url The URL to fetch
     * @param options Request options
     * @return Response data
     */
    suspend fun get(url: String, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Perform a POST request.
     *
     * @param url The URL to post to
     * @param body Request body
     * @param options Request options
     * @return Response data
     */
    suspend fun post(url: String, body: RequestBody, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Perform a form POST request.
     *
     * @param url The URL to post to
     * @param formData Form field key-value pairs
     * @param options Request options
     * @return Response data
     */
    suspend fun postForm(url: String, formData: Map<String, String>, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Download binary content (images, files).
     *
     * @param url The URL to download from
     * @param options Request options
     * @return Raw bytes
     */
    suspend fun downloadBytes(url: String, options: FetchOptions = FetchOptions()): ByteArray

    /**
     * Create a session-bound client with persistent cookies.
     *
     * @param sessionId Unique session identifier
     * @return A fetch client that maintains session state
     */
    fun createSession(sessionId: String): SessionFetchClient

    /**
     * Get or create a session for a connector.
     *
     * Sessions are reused across calls for the same connector,
     * maintaining cookies and authentication state.
     *
     * @param connectorName The connector name
     * @return A session-bound fetch client
     */
    fun getConnectorSession(connectorName: String): SessionFetchClient

    /**
     * Clear session for a connector.
     */
    fun clearConnectorSession(connectorName: String)

    /**
     * Close the client and release resources.
     */
    override fun close()
}

/**
 * Session-bound fetch client with persistent state.
 */
interface SessionFetchClient : Closeable {

    /**
     * Session identifier.
     */
    val sessionId: String

    /**
     * Perform a GET request.
     */
    suspend fun get(url: String, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Perform a POST request.
     */
    suspend fun post(url: String, body: RequestBody, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Perform a form POST request.
     */
    suspend fun postForm(url: String, formData: Map<String, String>, options: FetchOptions = FetchOptions()): FetchResponse

    /**
     * Download binary content.
     */
    suspend fun downloadBytes(url: String, options: FetchOptions = FetchOptions()): ByteArray

    /**
     * Get all cookies for this session.
     */
    fun getCookies(): List<HttpCookie>

    /**
     * Set cookies for this session.
     */
    fun setCookies(cookies: List<HttpCookie>)

    /**
     * Clear all cookies for this session.
     */
    fun clearCookies()

    /**
     * Set a default header that will be sent with all requests.
     */
    fun setDefaultHeader(name: String, value: String)

    /**
     * Remove a default header.
     */
    fun removeDefaultHeader(name: String)

    /**
     * Close this session.
     */
    override fun close()
}

/**
 * HTTP cookie representation.
 */
data class HttpCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val maxAge: Long? = null,  // Seconds until expiration, null = session
    val sameSite: String? = null  // Strict, Lax, None
)

/**
 * Options for fetch requests.
 */
data class FetchOptions(
    val headers: Map<String, String> = emptyMap(),
    val timeout: Long = 30_000,  // Request timeout in ms
    val followRedirects: Boolean = true,
    val maxRedirects: Int = 5,
    val retries: Int = 3,
    val retryDelay: Long = 1000,  // ms between retries
    val referer: String? = null,  // Convenience for Referer header
    val userAgent: String? = null,  // Override default user agent
    val acceptLanguage: String? = null
) {
    /**
     * Build headers map including convenience fields.
     */
    fun buildHeaders(): Map<String, String> {
        val result = headers.toMutableMap()
        referer?.let { result["Referer"] = it }
        userAgent?.let { result["User-Agent"] = it }
        acceptLanguage?.let { result["Accept-Language"] = it }
        return result
    }
}

/**
 * Request body for POST requests.
 */
sealed class RequestBody {

    /**
     * JSON body.
     */
    data class Json(val content: String) : RequestBody()

    /**
     * Form URL-encoded body.
     */
    data class Form(val fields: Map<String, String>) : RequestBody()

    /**
     * Raw binary body.
     */
    data class Binary(val bytes: ByteArray, val contentType: String) : RequestBody() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }

    /**
     * Plain text body.
     */
    data class Text(val content: String, val contentType: String = "text/plain") : RequestBody()

    companion object {
        fun json(content: String) = Json(content)
        fun form(fields: Map<String, String>) = Form(fields)
        fun binary(bytes: ByteArray, contentType: String) = Binary(bytes, contentType)
        fun text(content: String) = Text(content)
    }
}

/**
 * Response from a fetch request.
 */
data class FetchResponse(
    val url: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val cookies: List<HttpCookie> = emptyList(),
    val redirectedFrom: String? = null
) {
    /**
     * Check if response was successful (2xx).
     */
    val isSuccess: Boolean get() = statusCode in 200..299

    /**
     * Check if response was a redirect (3xx).
     */
    val isRedirect: Boolean get() = statusCode in 300..399

    /**
     * Check if response was a client error (4xx).
     */
    val isClientError: Boolean get() = statusCode in 400..499

    /**
     * Check if response was a server error (5xx).
     */
    val isServerError: Boolean get() = statusCode in 500..599

    /**
     * Get a single header value.
     */
    fun header(name: String): String? = headers[name]?.firstOrNull()

    /**
     * Get all values for a header.
     */
    fun headers(name: String): List<String> = headers[name] ?: emptyList()

    /**
     * Get Content-Type header.
     */
    val contentType: String? get() = header("Content-Type")

    /**
     * Throw exception if response was not successful.
     */
    fun requireSuccess(): FetchResponse {
        if (!isSuccess) {
            throw FetchException("HTTP $statusCode for $url", statusCode, body)
        }
        return this
    }
}

/**
 * Exception thrown on fetch errors.
 *
 * @param retryAfterSeconds Parsed `Retry-After` header value in seconds (for 429 responses).
 */
class FetchException(
    message: String,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)
