package dev.koenv.chaptervault.core.browser

import dev.koenv.chaptervault.core.config.BrowserPoolConfig
import java.io.Closeable

/**
 * Browser pool interface for managing shared browser instances.
 *
 * The pool manages a limited number of browser instances that can be shared
 * across multiple connectors. Each browser can have multiple contexts (sessions)
 * for isolation between different connector operations.
 *
 * Design goals:
 * - Keep resource footprint low (limited number of browsers)
 * - Provide isolation between connectors via contexts
 * - Handle browser crashes and recovery
 * - Support future instruction-based execution
 */
interface BrowserPool : Closeable {

    /**
     * Get pool configuration.
     */
    val config: BrowserPoolConfig

    /**
     * Acquire a browser session for use.
     *
     * The session provides an isolated browser context with its own cookies,
     * localStorage, and session state. Multiple sessions can share the same
     * underlying browser instance.
     *
     * @param sessionId Unique identifier for this session (e.g., connector name + operation ID)
     * @param options Optional session configuration
     * @return A browser session that must be released when done
     */
    suspend fun acquireSession(
        sessionId: String,
        options: BrowserSessionOptions = BrowserSessionOptions()
    ): BrowserSession

    /**
     * Release a browser session back to the pool.
     *
     * @param session The session to release
     * @param keepAlive If true, the session context is kept for potential reuse
     */
    suspend fun releaseSession(session: BrowserSession, keepAlive: Boolean = false)

    /**
     * Get a session for a specific connector (reuses existing if available).
     *
     * This is a convenience method that maintains session affinity per connector,
     * allowing cookies and state to persist across operations.
     *
     * @param connectorName The connector requesting the session
     * @return A browser session bound to this connector
     */
    suspend fun getConnectorSession(connectorName: String): BrowserSession

    /**
     * Clear all sessions for a connector.
     */
    suspend fun clearConnectorSessions(connectorName: String)

    /**
     * Get pool statistics.
     */
    fun getStats(): BrowserPoolStats

    /**
     * Check if the pool is healthy and browsers are available.
     */
    fun isHealthy(): Boolean

    /**
     * Shutdown the pool and close all browsers.
     */
    override fun close()
}

/**
 * Options for creating a browser session.
 */
data class BrowserSessionOptions(
    val isolatedContext: Boolean = true,  // Create new context vs reuse
    val blockImages: Boolean = false,      // Block image loading for speed
    val blockMedia: Boolean = false,       // Block media loading
    val customUserAgent: String? = null,   // Override user agent
    val extraHeaders: Map<String, String> = emptyMap(),
    val cookies: List<BrowserCookie> = emptyList(),  // Pre-set cookies
    val localStorage: Map<String, String> = emptyMap(),  // Pre-set localStorage
    val timeout: Long = 30_000  // Default operation timeout in ms
)

/**
 * Cookie for browser session.
 */
data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String = "Lax",  // Strict, Lax, None
    val expires: Long? = null  // Unix timestamp, null = session cookie
)

/**
 * Browser pool statistics.
 */
data class BrowserPoolStats(
    val totalBrowsers: Int,
    val activeBrowsers: Int,
    val totalSessions: Int,
    val activeSessions: Int,
    val waitingRequests: Int,
    val totalPagesCreated: Long,
    val totalPagesReleased: Long,
    val browserCrashes: Int,
    val averageSessionDurationMs: Long
)

/**
 * Browser session interface.
 *
 * Represents an isolated browser context that can be used for web operations.
 * Sessions should be released when no longer needed.
 */
interface BrowserSession : Closeable {

    /**
     * Unique session identifier.
     */
    val sessionId: String

    /**
     * Navigate to a URL and wait for the page to load.
     *
     * @param url The URL to navigate to
     * @param waitUntil When to consider navigation complete
     * @return Page content after load
     */
    suspend fun navigate(
        url: String,
        waitUntil: PageLoadState = PageLoadState.NETWORK_IDLE
    ): PageContent

    /**
     * Execute JavaScript in the current page context.
     *
     * @param script JavaScript code to execute
     * @return Result of the script execution (as JSON string)
     */
    suspend fun evaluate(script: String): String?

    /**
     * Wait for a selector to appear on the page.
     *
     * @param selector CSS selector to wait for
     * @param timeout Maximum wait time in milliseconds
     * @return True if element found, false if timeout
     */
    suspend fun waitForSelector(selector: String, timeout: Long = 30_000): Boolean

    /**
     * Click an element.
     *
     * @param selector CSS selector of element to click
     */
    suspend fun click(selector: String)

    /**
     * Type text into an input element.
     *
     * @param selector CSS selector of input element
     * @param text Text to type
     * @param delay Delay between keystrokes in ms (0 = instant)
     */
    suspend fun type(selector: String, text: String, delay: Long = 0)

    /**
     * Fill an input element (faster than type, no keystroke events).
     *
     * @param selector CSS selector of input element
     * @param value Value to fill
     */
    suspend fun fill(selector: String, value: String)

    /**
     * Get the current page HTML content.
     */
    suspend fun getContent(): String

    /**
     * Get all elements matching a selector.
     *
     * @param selector CSS selector
     * @return List of element data
     */
    suspend fun queryAll(selector: String): List<ElementData>

    /**
     * Get first element matching a selector.
     *
     * @param selector CSS selector
     * @return Element data or null
     */
    suspend fun queryFirst(selector: String): ElementData?

    /**
     * Scroll the page.
     *
     * @param x Horizontal scroll amount
     * @param y Vertical scroll amount
     */
    suspend fun scroll(x: Int = 0, y: Int = 0)

    /**
     * Scroll to the bottom of the page (for infinite scroll).
     *
     * @param maxScrolls Maximum number of scroll iterations
     * @param delayBetweenScrolls Delay between scrolls in ms
     * @return Number of scrolls performed
     */
    suspend fun scrollToBottom(maxScrolls: Int = 10, delayBetweenScrolls: Long = 500): Int

    /**
     * Take a screenshot of the current page.
     *
     * @param fullPage If true, capture entire scrollable page
     * @return Screenshot as PNG bytes
     */
    suspend fun screenshot(fullPage: Boolean = false): ByteArray

    /**
     * Get all cookies for the current context.
     */
    suspend fun getCookies(): List<BrowserCookie>

    /**
     * Set cookies in the current context.
     */
    suspend fun setCookies(cookies: List<BrowserCookie>)

    /**
     * Clear all cookies in the current context.
     */
    suspend fun clearCookies()

    /**
     * Get localStorage data.
     *
     * @param origin The origin to get localStorage for (e.g., "https://example.com")
     */
    suspend fun getLocalStorage(origin: String): Map<String, String>

    /**
     * Set localStorage data.
     *
     * @param origin The origin to set localStorage for
     * @param data Key-value pairs to set
     */
    suspend fun setLocalStorage(origin: String, data: Map<String, String>)

    /**
     * Download a file from a URL using the browser's context (with cookies/auth).
     *
     * @param url URL to download from
     * @param referer Referer header to send
     * @return File bytes
     */
    suspend fun downloadFile(url: String, referer: String? = null): ByteArray

    /**
     * Get the current page URL.
     */
    suspend fun getCurrentUrl(): String

    /**
     * Check if the session is still valid.
     */
    fun isValid(): Boolean

    /**
     * Close this session and release resources.
     */
    override fun close()
}

/**
 * Page load state for navigation.
 */
enum class PageLoadState {
    LOAD,           // Wait for 'load' event
    DOM_CONTENT,    // Wait for 'DOMContentLoaded' event
    NETWORK_IDLE    // Wait for network to be idle (no requests for 500ms)
}

/**
 * Page content after navigation.
 */
data class PageContent(
    val url: String,
    val html: String,
    val title: String?,
    val statusCode: Int?
)

/**
 * Data from a DOM element.
 */
data class ElementData(
    val tagName: String,
    val textContent: String?,
    val innerHTML: String?,
    val attributes: Map<String, String>,
    val isVisible: Boolean
) {
    fun attr(name: String): String? = attributes[name]
    fun href(): String? = attributes["href"]
    fun src(): String? = attributes["src"]
}
