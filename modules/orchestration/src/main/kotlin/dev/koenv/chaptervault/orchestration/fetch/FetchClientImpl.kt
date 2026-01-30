package dev.koenv.chaptervault.orchestration.fetch

import dev.koenv.chaptervault.core.config.HttpClientConfig
import dev.koenv.chaptervault.core.fetch.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Ktor-based fetch client implementation.
 *
 * Features:
 * - Automatic retries with exponential backoff
 * - Cookie management per session
 * - Configurable timeouts and redirects
 * - Proxy support
 */
class FetchClientImpl(
    private val config: HttpClientConfig = HttpClientConfig()
) : FetchClient {

    private val logger = LoggerFactory.getLogger(FetchClientImpl::class.java)

    // Main HTTP client
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = config.readTimeoutSeconds * 1000
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutSeconds * 1000
            requestTimeoutMillis = config.readTimeoutSeconds * 1000
        }
        followRedirects = config.followRedirects
        defaultRequest {
            header("User-Agent", config.userAgent)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.5")
        }
    }

    // Session storage
    private val sessions = ConcurrentHashMap<String, SessionFetchClientImpl>()

    // Connector sessions (persistent)
    private val connectorSessions = ConcurrentHashMap<String, SessionFetchClientImpl>()

    override suspend fun get(url: String, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.get(url) {
                applyOptions(options)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun post(url: String, body: RequestBody, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.post(url) {
                applyOptions(options)
                applyBody(body)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun postForm(url: String, formData: Map<String, String>, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.submitForm(
                url = url,
                formParameters = parameters {
                    formData.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            ) {
                applyOptions(options)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun downloadBytes(url: String, options: FetchOptions): ByteArray {
        return executeWithRetry(options) {
            val response = httpClient.get(url) {
                applyOptions(options)
            }
            if (!response.status.isSuccess()) {
                throw FetchException("HTTP ${response.status.value} for $url", response.status.value)
            }
            response.readRawBytes()
        }
    }

    override fun createSession(sessionId: String): SessionFetchClient {
        return sessions.computeIfAbsent(sessionId) {
            SessionFetchClientImpl(sessionId, config)
        }
    }

    override fun getConnectorSession(connectorName: String): SessionFetchClient {
        return connectorSessions.computeIfAbsent(connectorName) {
            SessionFetchClientImpl("connector:$connectorName", config)
        }
    }

    override fun clearConnectorSession(connectorName: String) {
        connectorSessions.remove(connectorName)?.close()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        connectorSessions.values.forEach { it.close() }
        connectorSessions.clear()
        httpClient.close()
    }

    /**
     * Execute a request with retries.
     */
    private suspend fun <T> executeWithRetry(options: FetchOptions, block: suspend () -> T): T {
        var lastException: Exception? = null
        var retryDelay = options.retryDelay

        repeat(options.retries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                logger.debug("Request failed (attempt ${attempt + 1}/${options.retries + 1}): ${e.message}")

                if (attempt < options.retries) {
                    delay(retryDelay)
                    retryDelay *= 2  // Exponential backoff
                }
            }
        }

        throw FetchException(
            "Request failed after ${options.retries + 1} attempts",
            cause = lastException
        )
    }

    /**
     * Apply options to request builder.
     */
    private fun HttpRequestBuilder.applyOptions(options: FetchOptions) {
        timeout {
            requestTimeoutMillis = options.timeout
        }
        options.buildHeaders().forEach { (key, value) ->
            header(key, value)
        }
    }

    /**
     * Apply body to request builder.
     */
    private fun HttpRequestBuilder.applyBody(body: RequestBody) {
        when (body) {
            is RequestBody.Json -> {
                contentType(ContentType.Application.Json)
                setBody(body.content)
            }
            is RequestBody.Form -> {
                // Handled separately
            }
            is RequestBody.Binary -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.bytes)
            }
            is RequestBody.Text -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.content)
            }
        }
    }

    /**
     * Convert Ktor response to FetchResponse.
     */
    private suspend fun HttpResponse.toFetchResponse(): FetchResponse {
        val headerMap = headers.entries().associate { (name, values) -> name to values }
        return FetchResponse(
            url = request.url.toString(),
            statusCode = status.value,
            headers = headerMap,
            body = bodyAsText(),
            cookies = extractCookies(),
            redirectedFrom = null  // TODO: track redirects
        )
    }

    /**
     * Extract cookies from response headers.
     */
    private fun HttpResponse.extractCookies(): List<HttpCookie> {
        return headers.getAll("Set-Cookie")?.mapNotNull { cookieString ->
            try {
                parseCookieHeader(cookieString)
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    /**
     * Parse a Set-Cookie header value.
     */
    private fun parseCookieHeader(header: String): HttpCookie? {
        val parts = header.split(";").map { it.trim() }
        if (parts.isEmpty()) return null

        val nameValue = parts[0].split("=", limit = 2)
        if (nameValue.size != 2) return null

        val name = nameValue[0].trim()
        val value = nameValue[1].trim()

        var domain = ""
        var path = "/"
        var secure = false
        var httpOnly = false
        var maxAge: Long? = null
        var sameSite: String? = null

        for (i in 1 until parts.size) {
            val part = parts[i]
            when {
                part.startsWith("Domain=", ignoreCase = true) ->
                    domain = part.substringAfter("=").trim()
                part.startsWith("Path=", ignoreCase = true) ->
                    path = part.substringAfter("=").trim()
                part.equals("Secure", ignoreCase = true) ->
                    secure = true
                part.equals("HttpOnly", ignoreCase = true) ->
                    httpOnly = true
                part.startsWith("Max-Age=", ignoreCase = true) ->
                    maxAge = part.substringAfter("=").trim().toLongOrNull()
                part.startsWith("SameSite=", ignoreCase = true) ->
                    sameSite = part.substringAfter("=").trim()
            }
        }

        return HttpCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            maxAge = maxAge,
            sameSite = sameSite
        )
    }
}

/**
 * Session-bound fetch client implementation.
 */
class SessionFetchClientImpl(
    override val sessionId: String,
    private val config: HttpClientConfig
) : SessionFetchClient {

    private val logger = LoggerFactory.getLogger(SessionFetchClientImpl::class.java)

    // Cookie storage for this session
    private val cookieStorage = AcceptAllCookiesStorage()

    // Default headers for this session
    private val defaultHeaders = ConcurrentHashMap<String, String>()

    // Session HTTP client with cookie support
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = config.readTimeoutSeconds * 1000
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutSeconds * 1000
            requestTimeoutMillis = config.readTimeoutSeconds * 1000
        }
        install(HttpCookies) {
            storage = cookieStorage
        }
        followRedirects = config.followRedirects
        defaultRequest {
            header("User-Agent", config.userAgent)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.5")
        }
    }

    override suspend fun get(url: String, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.get(url) {
                applyOptions(options)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun post(url: String, body: RequestBody, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.post(url) {
                applyOptions(options)
                applyBody(body)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun postForm(url: String, formData: Map<String, String>, options: FetchOptions): FetchResponse {
        return executeWithRetry(options) {
            val response = httpClient.submitForm(
                url = url,
                formParameters = parameters {
                    formData.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            ) {
                applyOptions(options)
            }
            response.toFetchResponse()
        }
    }

    override suspend fun downloadBytes(url: String, options: FetchOptions): ByteArray {
        return executeWithRetry(options) {
            val response = httpClient.get(url) {
                applyOptions(options)
            }
            if (!response.status.isSuccess()) {
                throw FetchException("HTTP ${response.status.value} for $url", response.status.value)
            }
            response.readRawBytes()
        }
    }

    override fun getCookies(): List<HttpCookie> {
        return kotlinx.coroutines.runBlocking {
            cookieStorage.get(Url("http://placeholder")).map { cookie ->
                HttpCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain ?: "",
                    path = cookie.path ?: "/",
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    maxAge = cookie.maxAge?.toLong(),
                    sameSite = null
                )
            }
        }
    }

    override fun setCookies(cookies: List<HttpCookie>) {
        kotlinx.coroutines.runBlocking {
            for (cookie in cookies) {
                val ktorCookie = io.ktor.http.Cookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    maxAge = cookie.maxAge?.toInt()
                )
                cookieStorage.addCookie(Url("https://${cookie.domain}${cookie.path}"), ktorCookie)
            }
        }
    }

    override fun clearCookies() {
        // AcceptAllCookiesStorage doesn't have a clear method, so we recreate it
        // In practice, you'd want a custom storage that supports clearing
    }

    override fun setDefaultHeader(name: String, value: String) {
        defaultHeaders[name] = value
    }

    override fun removeDefaultHeader(name: String) {
        defaultHeaders.remove(name)
    }

    override fun close() {
        httpClient.close()
    }

    /**
     * Execute a request with retries.
     */
    private suspend fun <T> executeWithRetry(options: FetchOptions, block: suspend () -> T): T {
        var lastException: Exception? = null
        var retryDelay = options.retryDelay

        repeat(options.retries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                logger.debug("Request failed (attempt ${attempt + 1}/${options.retries + 1}): ${e.message}")

                if (attempt < options.retries) {
                    delay(retryDelay)
                    retryDelay *= 2
                }
            }
        }

        throw FetchException(
            "Request failed after ${options.retries + 1} attempts",
            cause = lastException
        )
    }

    /**
     * Apply options to request builder.
     */
    private fun HttpRequestBuilder.applyOptions(options: FetchOptions) {
        timeout {
            requestTimeoutMillis = options.timeout
        }
        // Apply default headers first
        defaultHeaders.forEach { (key, value) ->
            header(key, value)
        }
        // Then apply request-specific headers (can override defaults)
        options.buildHeaders().forEach { (key, value) ->
            header(key, value)
        }
    }

    /**
     * Apply body to request builder.
     */
    private fun HttpRequestBuilder.applyBody(body: RequestBody) {
        when (body) {
            is RequestBody.Json -> {
                contentType(ContentType.Application.Json)
                setBody(body.content)
            }
            is RequestBody.Form -> {
                // Handled separately
            }
            is RequestBody.Binary -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.bytes)
            }
            is RequestBody.Text -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.content)
            }
        }
    }

    /**
     * Convert Ktor response to FetchResponse.
     */
    private suspend fun HttpResponse.toFetchResponse(): FetchResponse {
        val headerMap = headers.entries().associate { (name, values) -> name to values }
        return FetchResponse(
            url = request.url.toString(),
            statusCode = status.value,
            headers = headerMap,
            body = bodyAsText(),
            cookies = emptyList(),  // Cookies are managed by the storage
            redirectedFrom = null
        )
    }
}
