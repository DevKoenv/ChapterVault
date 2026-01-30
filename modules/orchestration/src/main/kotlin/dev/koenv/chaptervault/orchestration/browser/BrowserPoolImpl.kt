package dev.koenv.chaptervault.orchestration.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import dev.koenv.chaptervault.core.browser.*
import dev.koenv.chaptervault.core.config.BrowserPoolConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Playwright-based browser pool implementation.
 *
 * Features:
 * - Manages a limited pool of browser instances
 * - Multiple contexts per browser for isolation
 * - Automatic cleanup of idle browsers/contexts
 * - Session affinity for connectors
 * - Crash recovery
 */
class BrowserPoolImpl(
    override val config: BrowserPoolConfig
) : BrowserPool {

    private val logger = LoggerFactory.getLogger(BrowserPoolImpl::class.java)

    // Playwright instance
    private var playwright: Playwright? = null
    private val playwrightMutex = Mutex()

    // Browser instances
    private data class ManagedBrowser(
        val browser: Browser,
        val createdAt: Instant,
        var lastUsedAt: Instant,
        val contexts: MutableList<ManagedContext> = mutableListOf()
    )

    private data class ManagedContext(
        val context: BrowserContext,
        val sessionId: String,
        val createdAt: Instant,
        var lastUsedAt: Instant,
        var inUse: Boolean = false
    )

    private val browsers = mutableListOf<ManagedBrowser>()
    private val browsersMutex = Mutex()

    // Session management
    private val connectorSessions = ConcurrentHashMap<String, BrowserSessionImpl>()

    // Semaphore to limit concurrent browser operations
    private val browserSemaphore = Semaphore(config.maxBrowsers * config.maxContextsPerBrowser)

    // Statistics
    private val stats = PoolStats()

    // Cleanup job
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    // HTTP client for file downloads
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 60_000
        }
    }

    init {
        if (config.enabled) {
            startCleanupJob()
        }
    }

    /**
     * Ensure Playwright is initialized.
     */
    private suspend fun ensurePlaywright(): Playwright {
        return playwrightMutex.withLock {
            playwright ?: run {
                logger.info("Initializing Playwright...")
                val pw = withContext(Dispatchers.IO) {
                    Playwright.create()
                }
                playwright = pw
                logger.info("Playwright initialized")
                pw
            }
        }
    }

    /**
     * Get or create a browser instance.
     */
    private suspend fun acquireBrowser(): ManagedBrowser {
        return browsersMutex.withLock {
            // Find existing browser with capacity
            val availableBrowser = browsers.find { managed ->
                managed.browser.isConnected &&
                managed.contexts.count { it.inUse } < config.maxContextsPerBrowser
            }

            if (availableBrowser != null) {
                availableBrowser.lastUsedAt = Instant.now()
                return@withLock availableBrowser
            }

            // Check if we can create a new browser
            if (browsers.size >= config.maxBrowsers) {
                // Wait for a browser to become available
                // Remove dead browsers first
                browsers.removeAll { !it.browser.isConnected }

                if (browsers.size >= config.maxBrowsers) {
                    // Find least recently used browser and evict oldest context
                    val lru = browsers.minByOrNull { it.lastUsedAt }
                    if (lru != null) {
                        val idleContext = lru.contexts.filter { !it.inUse }.minByOrNull { it.lastUsedAt }
                        if (idleContext != null) {
                            withContext(Dispatchers.IO) {
                                idleContext.context.close()
                            }
                            lru.contexts.remove(idleContext)
                            stats.totalPagesReleased.incrementAndGet()
                        }
                    }
                }

                if (browsers.size >= config.maxBrowsers) {
                    throw IllegalStateException("Browser pool exhausted (max: ${config.maxBrowsers})")
                }
            }

            // Create new browser
            val pw = ensurePlaywright()
            val browser = withContext(Dispatchers.IO) {
                val browserType = when (config.browserType.lowercase()) {
                    "firefox" -> pw.firefox()
                    "webkit" -> pw.webkit()
                    else -> pw.chromium()
                }

                val launchOptions = BrowserType.LaunchOptions()
                    .setHeadless(config.headless)
                    .setArgs(buildBrowserArgs())

                browserType.launch(launchOptions)
            }

            val managed = ManagedBrowser(
                browser = browser,
                createdAt = Instant.now(),
                lastUsedAt = Instant.now()
            )
            browsers.add(managed)
            stats.activeBrowsers.incrementAndGet()

            logger.info("Created new browser instance (total: ${browsers.size})")
            managed
        }
    }

    /**
     * Build browser launch arguments.
     */
    private fun buildBrowserArgs(): List<String> {
        val args = mutableListOf(
            "--disable-gpu",
            "--disable-dev-shm-usage",
            "--no-sandbox",
            "--disable-setuid-sandbox"
        )
        args.addAll(config.extraArgs)
        return args
    }

    override suspend fun acquireSession(
        sessionId: String,
        options: BrowserSessionOptions
    ): BrowserSession {
        if (!config.enabled) {
            throw IllegalStateException("Browser pool is disabled")
        }

        return browserSemaphore.withPermit {
            val browser = acquireBrowser()

            browsersMutex.withLock {
                // Check for existing context for this session
                if (!options.isolatedContext) {
                    val existing = browser.contexts.find { it.sessionId == sessionId && !it.inUse }
                    if (existing != null) {
                        existing.inUse = true
                        existing.lastUsedAt = Instant.now()
                        return@withPermit BrowserSessionImpl(
                            sessionId = sessionId,
                            context = existing.context,
                            pool = this,
                            httpClient = httpClient
                        )
                    }
                }

                // Create new context
                val context = withContext(Dispatchers.IO) {
                    browser.browser.newContext(buildContextOptions(options))
                }

                // Apply pre-set cookies
                if (options.cookies.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val playwrightCookies = options.cookies.map { cookie ->
                            com.microsoft.playwright.options.Cookie(cookie.name, cookie.value)
                                .setDomain(cookie.domain)
                                .setPath(cookie.path)
                                .setSecure(cookie.secure)
                                .setHttpOnly(cookie.httpOnly)
                                .setSameSite(
                                    when (cookie.sameSite.lowercase()) {
                                        "strict" -> com.microsoft.playwright.options.SameSiteAttribute.STRICT
                                        "none" -> com.microsoft.playwright.options.SameSiteAttribute.NONE
                                        else -> com.microsoft.playwright.options.SameSiteAttribute.LAX
                                    }
                                )
                                .also { c -> cookie.expires?.let { c.setExpires(it.toDouble()) } }
                        }
                        context.addCookies(playwrightCookies)
                    }
                }

                val managed = ManagedContext(
                    context = context,
                    sessionId = sessionId,
                    createdAt = Instant.now(),
                    lastUsedAt = Instant.now(),
                    inUse = true
                )
                browser.contexts.add(managed)
                stats.totalPagesCreated.incrementAndGet()
                stats.activeSessions.incrementAndGet()

                BrowserSessionImpl(
                    sessionId = sessionId,
                    context = context,
                    pool = this,
                    httpClient = httpClient
                )
            }
        }
    }

    /**
     * Build browser context options.
     */
    private fun buildContextOptions(options: BrowserSessionOptions): Browser.NewContextOptions {
        return Browser.NewContextOptions()
            .setUserAgent(options.customUserAgent ?: config.userAgent ?: DEFAULT_USER_AGENT)
            .setViewportSize(config.viewportWidth, config.viewportHeight)
            .setLocale(config.locale)
            .apply {
                config.timezone?.let { setTimezoneId(it) }
                if (options.extraHeaders.isNotEmpty()) {
                    setExtraHTTPHeaders(options.extraHeaders)
                }
            }
    }

    override suspend fun releaseSession(session: BrowserSession, keepAlive: Boolean) {
        val impl = session as? BrowserSessionImpl ?: return

        browsersMutex.withLock {
            for (browser in browsers) {
                val managed = browser.contexts.find { it.context == impl.context }
                if (managed != null) {
                    managed.inUse = false
                    managed.lastUsedAt = Instant.now()

                    if (!keepAlive) {
                        withContext(Dispatchers.IO) {
                            try {
                                managed.context.close()
                            } catch (e: Exception) {
                                logger.debug("Error closing context: ${e.message}")
                            }
                        }
                        browser.contexts.remove(managed)
                        stats.totalPagesReleased.incrementAndGet()
                    }

                    stats.activeSessions.decrementAndGet()
                    return@withLock
                }
            }
        }
    }

    override suspend fun getConnectorSession(connectorName: String): BrowserSession {
        return connectorSessions.computeIfAbsent(connectorName) {
            runBlocking {
                acquireSession(
                    sessionId = "connector:$connectorName",
                    options = BrowserSessionOptions(isolatedContext = false)
                ) as BrowserSessionImpl
            }
        }
    }

    override suspend fun clearConnectorSessions(connectorName: String) {
        connectorSessions.remove(connectorName)?.let { session ->
            releaseSession(session, keepAlive = false)
        }
    }

    override fun getStats(): BrowserPoolStats {
        return BrowserPoolStats(
            totalBrowsers = browsers.size,
            activeBrowsers = stats.activeBrowsers.get(),
            totalSessions = browsers.sumOf { it.contexts.size },
            activeSessions = stats.activeSessions.get(),
            waitingRequests = config.maxBrowsers * config.maxContextsPerBrowser - browserSemaphore.availablePermits,
            totalPagesCreated = stats.totalPagesCreated.get(),
            totalPagesReleased = stats.totalPagesReleased.get(),
            browserCrashes = stats.browserCrashes.get(),
            averageSessionDurationMs = 0  // TODO: track this
        )
    }

    override fun isHealthy(): Boolean {
        if (!config.enabled) return true
        return browsers.any { it.browser.isConnected }
    }

    /**
     * Start the cleanup job.
     */
    private fun startCleanupJob() {
        cleanupJob = cleanupScope.launch {
            while (isActive) {
                delay(30_000)  // Check every 30 seconds
                cleanupIdleResources()
            }
        }
    }

    /**
     * Clean up idle browsers and contexts.
     */
    private suspend fun cleanupIdleResources() {
        val now = Instant.now()
        val contextTimeout = config.contextIdleTimeoutSeconds
        val browserTimeout = config.browserIdleTimeoutSeconds

        browsersMutex.withLock {
            // Clean up idle contexts
            for (browser in browsers) {
                val idleContexts = browser.contexts.filter { context ->
                    !context.inUse &&
                    context.lastUsedAt.plusSeconds(contextTimeout).isBefore(now)
                }

                for (context in idleContexts) {
                    withContext(Dispatchers.IO) {
                        try {
                            context.context.close()
                        } catch (e: Exception) {
                            logger.debug("Error closing idle context: ${e.message}")
                        }
                    }
                    browser.contexts.remove(context)
                    stats.totalPagesReleased.incrementAndGet()
                    logger.debug("Closed idle context: ${context.sessionId}")
                }
            }

            // Clean up idle/disconnected browsers
            val browsersToRemove = browsers.filter { browser ->
                !browser.browser.isConnected ||
                (browser.contexts.isEmpty() && browser.lastUsedAt.plusSeconds(browserTimeout).isBefore(now))
            }

            for (browser in browsersToRemove) {
                withContext(Dispatchers.IO) {
                    try {
                        browser.browser.close()
                    } catch (e: Exception) {
                        logger.debug("Error closing idle browser: ${e.message}")
                    }
                }
                browsers.remove(browser)
                stats.activeBrowsers.decrementAndGet()
                logger.debug("Closed idle browser")
            }
        }
    }

    override fun close() {
        logger.info("Shutting down browser pool...")

        cleanupJob?.cancel()
        cleanupScope.cancel()

        runBlocking {
            browsersMutex.withLock {
                for (browser in browsers) {
                    for (context in browser.contexts) {
                        try {
                            context.context.close()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    try {
                        browser.browser.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                browsers.clear()
            }

            playwrightMutex.withLock {
                playwright?.close()
                playwright = null
            }
        }

        httpClient.close()
        connectorSessions.clear()

        logger.info("Browser pool shut down")
    }

    /**
     * Internal statistics tracking.
     */
    private class PoolStats {
        val activeBrowsers = AtomicInteger(0)
        val activeSessions = AtomicInteger(0)
        val totalPagesCreated = AtomicLong(0)
        val totalPagesReleased = AtomicLong(0)
        val browserCrashes = AtomicInteger(0)
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/**
 * Browser session implementation using Playwright.
 */
class BrowserSessionImpl(
    override val sessionId: String,
    internal val context: BrowserContext,
    private val pool: BrowserPoolImpl,
    private val httpClient: HttpClient
) : BrowserSession {

    private val logger = LoggerFactory.getLogger(BrowserSessionImpl::class.java)
    private var currentPage: Page? = null
    private val pageMutex = Mutex()

    /**
     * Get or create a page for operations.
     */
    private suspend fun ensurePage(): Page {
        return pageMutex.withLock {
            currentPage?.takeIf { !it.isClosed } ?: run {
                val page = withContext(Dispatchers.IO) {
                    context.newPage()
                }
                currentPage = page
                page
            }
        }
    }

    override suspend fun navigate(url: String, waitUntil: PageLoadState): PageContent {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            val waitState = when (waitUntil) {
                PageLoadState.LOAD -> WaitUntilState.LOAD
                PageLoadState.DOM_CONTENT -> WaitUntilState.DOMCONTENTLOADED
                PageLoadState.NETWORK_IDLE -> WaitUntilState.NETWORKIDLE
            }

            val response = page.navigate(url, Page.NavigateOptions().setWaitUntil(waitState))

            PageContent(
                url = page.url(),
                html = page.content(),
                title = page.title(),
                statusCode = response?.status()
            )
        }
    }

    override suspend fun evaluate(script: String): String? {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            val result = page.evaluate(script)
            result?.toString()
        }
    }

    override suspend fun waitForSelector(selector: String, timeout: Long): Boolean {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            try {
                page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(timeout.toDouble()))
                true
            } catch (e: TimeoutError) {
                false
            }
        }
    }

    override suspend fun click(selector: String) {
        val page = ensurePage()
        withContext(Dispatchers.IO) {
            page.click(selector)
        }
    }

    override suspend fun type(selector: String, text: String, delay: Long) {
        val page = ensurePage()
        withContext(Dispatchers.IO) {
            page.locator(selector).type(text, Locator.TypeOptions().setDelay(delay.toDouble()))
        }
    }

    override suspend fun fill(selector: String, value: String) {
        val page = ensurePage()
        withContext(Dispatchers.IO) {
            page.fill(selector, value)
        }
    }

    override suspend fun getContent(): String {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            page.content()
        }
    }

    override suspend fun queryAll(selector: String): List<ElementData> {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            page.locator(selector).all().map { locator ->
                ElementData(
                    tagName = locator.evaluate("el => el.tagName")?.toString()?.lowercase() ?: "",
                    textContent = locator.textContent(),
                    innerHTML = locator.innerHTML(),
                    attributes = extractAttributes(locator),
                    isVisible = locator.isVisible
                )
            }
        }
    }

    override suspend fun queryFirst(selector: String): ElementData? {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            val locator = page.locator(selector).first()
            if (locator.count() == 0) return@withContext null

            ElementData(
                tagName = locator.evaluate("el => el.tagName")?.toString()?.lowercase() ?: "",
                textContent = locator.textContent(),
                innerHTML = locator.innerHTML(),
                attributes = extractAttributes(locator),
                isVisible = locator.isVisible
            )
        }
    }

    private fun extractAttributes(locator: Locator): Map<String, String> {
        val attrsJson = locator.evaluate("""
            el => {
                const attrs = {};
                for (const attr of el.attributes) {
                    attrs[attr.name] = attr.value;
                }
                return JSON.stringify(attrs);
            }
        """)?.toString() ?: "{}"

        return try {
            // Simple JSON parsing for attribute map
            attrsJson.removeSurrounding("{", "}")
                .split(",")
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (key, value) = pair.split(":", limit = 2)
                    key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
                }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override suspend fun scroll(x: Int, y: Int) {
        val page = ensurePage()
        withContext(Dispatchers.IO) {
            page.evaluate("window.scrollBy($x, $y)")
        }
    }

    override suspend fun scrollToBottom(maxScrolls: Int, delayBetweenScrolls: Long): Int {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            var scrolls = 0
            var previousHeight = 0L

            while (scrolls < maxScrolls) {
                val currentHeight = (page.evaluate("document.body.scrollHeight") as Number).toLong()
                if (currentHeight == previousHeight) break

                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                delay(delayBetweenScrolls)

                previousHeight = currentHeight
                scrolls++
            }

            scrolls
        }
    }

    override suspend fun screenshot(fullPage: Boolean): ByteArray {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            page.screenshot(Page.ScreenshotOptions().setFullPage(fullPage))
        }
    }

    override suspend fun getCookies(): List<BrowserCookie> {
        return withContext(Dispatchers.IO) {
            context.cookies().map { cookie ->
                BrowserCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    sameSite = cookie.sameSite?.name ?: "Lax",
                    expires = cookie.expires?.toLong()
                )
            }
        }
    }

    override suspend fun setCookies(cookies: List<BrowserCookie>) {
        withContext(Dispatchers.IO) {
            val playwrightCookies = cookies.map { cookie ->
                com.microsoft.playwright.options.Cookie(cookie.name, cookie.value)
                    .setDomain(cookie.domain)
                    .setPath(cookie.path)
                    .setSecure(cookie.secure)
                    .setHttpOnly(cookie.httpOnly)
                    .also { c -> cookie.expires?.let { c.setExpires(it.toDouble()) } }
            }
            context.addCookies(playwrightCookies)
        }
    }

    override suspend fun clearCookies() {
        withContext(Dispatchers.IO) {
            context.clearCookies()
        }
    }

    override suspend fun getLocalStorage(origin: String): Map<String, String> {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            // Navigate to origin if not already there
            if (!page.url().startsWith(origin)) {
                page.navigate(origin)
            }

            val result = page.evaluate("""
                () => {
                    const items = {};
                    for (let i = 0; i < localStorage.length; i++) {
                        const key = localStorage.key(i);
                        items[key] = localStorage.getItem(key);
                    }
                    return items;
                }
            """)

            @Suppress("UNCHECKED_CAST")
            (result as? Map<String, Any>)?.mapValues { it.value.toString() } ?: emptyMap()
        }
    }

    override suspend fun setLocalStorage(origin: String, data: Map<String, String>) {
        val page = ensurePage()
        withContext(Dispatchers.IO) {
            // Navigate to origin if not already there
            if (!page.url().startsWith(origin)) {
                page.navigate(origin)
            }

            for ((key, value) in data) {
                page.evaluate("localStorage.setItem('$key', '$value')")
            }
        }
    }

    override suspend fun downloadFile(url: String, referer: String?): ByteArray {
        // Get cookies from browser context for authenticated download
        val cookies = getCookies()
        val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }

        return withContext(Dispatchers.IO) {
            httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                referer?.let { header("Referer", it) }
                if (cookieHeader.isNotBlank()) {
                    header("Cookie", cookieHeader)
                }
            }.readRawBytes()
        }
    }

    override suspend fun getCurrentUrl(): String {
        val page = ensurePage()
        return withContext(Dispatchers.IO) {
            page.url()
        }
    }

    override fun isValid(): Boolean {
        return try {
            !context.pages().all { it.isClosed }
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        runBlocking {
            pageMutex.withLock {
                currentPage?.let {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                currentPage = null
            }
            pool.releaseSession(this@BrowserSessionImpl, keepAlive = false)
        }
    }
}
