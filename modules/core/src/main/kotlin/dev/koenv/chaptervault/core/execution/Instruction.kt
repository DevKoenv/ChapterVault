package dev.koenv.chaptervault.core.execution

/**
 * Base sealed class for all execution instructions.
 *
 * Instructions describe WHAT to do, not HOW to do it.
 * They are executed by an Executor which handles the actual implementation.
 *
 * This architecture enables:
 * - External/distributed runners
 * - Testability (verify instructions without executing)
 * - Replay and debugging
 * - Rate limiting at executor level
 * - Resource pooling and management
 */
sealed class Instruction {
    /**
     * Unique identifier for this instruction (for tracking/debugging).
     */
    abstract val id: String

    /**
     * Optional timeout override in milliseconds.
     */
    open val timeout: Long? = null
}

// ============================================================================
// HTTP Instructions
// ============================================================================

/**
 * Fetch HTML content from a URL.
 */
data class FetchHtml(
    override val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Fetch JSON content from a URL.
 */
data class FetchJson(
    override val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Fetch binary content (images, files) from a URL.
 */
data class FetchBytes(
    override val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * POST form data to a URL.
 */
data class PostForm(
    override val id: String,
    val url: String,
    val formData: Map<String, String>,
    val headers: Map<String, String> = emptyMap(),
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * POST JSON data to a URL.
 */
data class PostJson(
    override val id: String,
    val url: String,
    val jsonBody: String,
    val headers: Map<String, String> = emptyMap(),
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

// ============================================================================
// Browser Instructions
// ============================================================================

/**
 * Navigate browser to a URL.
 */
data class BrowserNavigate(
    override val id: String,
    val url: String,
    val waitUntil: WaitCondition = WaitCondition.NETWORK_IDLE,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Wait condition for browser navigation.
 */
enum class WaitCondition {
    LOAD,           // Wait for 'load' event
    DOM_CONTENT,    // Wait for 'DOMContentLoaded'
    NETWORK_IDLE    // Wait for network to be idle
}

/**
 * Wait for a selector to appear.
 */
data class BrowserWaitForSelector(
    override val id: String,
    val selector: String,
    override val timeout: Long? = 30_000
) : Instruction()

/**
 * Click an element.
 */
data class BrowserClick(
    override val id: String,
    val selector: String
) : Instruction()

/**
 * Fill an input field.
 */
data class BrowserFill(
    override val id: String,
    val selector: String,
    val value: String
) : Instruction()

/**
 * Type text with keystroke events.
 */
data class BrowserType(
    override val id: String,
    val selector: String,
    val text: String,
    val delayMs: Long = 0
) : Instruction()

/**
 * Query all elements matching a selector.
 */
data class BrowserQueryAll(
    override val id: String,
    val selector: String
) : Instruction()

/**
 * Query first element matching a selector.
 */
data class BrowserQueryFirst(
    override val id: String,
    val selector: String
) : Instruction()

/**
 * Execute JavaScript in page context.
 */
data class BrowserEvaluate(
    override val id: String,
    val script: String
) : Instruction()

/**
 * Get current page HTML content.
 */
data class BrowserGetContent(
    override val id: String
) : Instruction()

/**
 * Get current page URL.
 */
data class BrowserGetUrl(
    override val id: String
) : Instruction()

/**
 * Scroll the page.
 */
data class BrowserScroll(
    override val id: String,
    val x: Int = 0,
    val y: Int = 0
) : Instruction()

/**
 * Scroll to bottom of page (for infinite scroll).
 */
data class BrowserScrollToBottom(
    override val id: String,
    val maxScrolls: Int = 10,
    val delayBetweenScrollsMs: Long = 500
) : Instruction()

/**
 * Download file using browser context (preserves cookies/auth).
 */
data class BrowserDownloadFile(
    override val id: String,
    val url: String,
    val referer: String? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Get cookies from browser context.
 */
data class BrowserGetCookies(
    override val id: String
) : Instruction()

/**
 * Set cookies in browser context.
 */
data class BrowserSetCookies(
    override val id: String,
    val cookies: List<CookieData>
) : Instruction()

/**
 * Cookie data for browser instructions.
 */
data class CookieData(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String = "Lax",
    val expires: Long? = null
)

// ============================================================================
// Composite Instructions
// ============================================================================

/**
 * Execute multiple instructions in sequence.
 * Results are returned as a list in order.
 */
data class Sequence(
    override val id: String,
    val instructions: List<Instruction>
) : Instruction()

/**
 * Execute multiple instructions in parallel.
 * Results are returned as a map of instruction ID to result.
 */
data class Parallel(
    override val id: String,
    val instructions: List<Instruction>
) : Instruction()

/**
 * Conditional execution based on a previous result.
 */
data class Conditional(
    override val id: String,
    val condition: String,  // Reference to previous instruction ID
    val predicate: (Any?) -> Boolean,
    val thenInstruction: Instruction,
    val elseInstruction: Instruction? = null
) : Instruction()

/**
 * Retry an instruction on failure.
 */
data class Retry(
    override val id: String,
    val instruction: Instruction,
    val maxRetries: Int = 3,
    val delayMs: Long = 1000,
    val exponentialBackoff: Boolean = true
) : Instruction()

// ============================================================================
// Declarative Extraction Instructions
// ============================================================================

/**
 * Fetch a URL and return a parsed Document.
 *
 * This is like FetchHtml but returns a Document abstraction
 * instead of raw HTML string.
 */
data class FetchDocument(
    override val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Fetch a URL and extract structured data using an ExtractionSpec.
 *
 * This combines fetching and parsing into a single declarative instruction.
 * Instead of returning raw HTML that the connector must parse, it returns
 * structured data based on the provided extraction specification.
 *
 * Example:
 * ```kotlin
 * extractData(url = searchUrl) {
 *     nestedList("results", ".manga-card") {
 *         href("url", "a")
 *         text("title", ".title")
 *     }
 * }
 * ```
 */
data class ExtractData(
    override val id: String,
    val url: String,
    val spec: ExtractionSpec,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    override val timeout: Long? = null,
    val rateLimitBucket: String? = null
) : Instruction()

/**
 * Download multiple files with concurrency control and retry logic.
 *
 * This replaces the pattern of creating N individual FetchBytes instructions
 * and manually handling results. The executor handles:
 * - Per-item retries with exponential backoff
 * - Partial failure tracking (some succeed, some fail)
 *
 * Concurrency is controlled by the rate-limit bucket declared on each item
 * via [DownloadItem.rateLimitBucket]. The bucket's [maxConcurrent][dev.koenv.chaptervault.core.ratelimit.RateLimitConfig.maxConcurrent]
 * is the single source of truth for how many requests are in-flight at once.
 *
 * Example:
 * ```kotlin
 * bulkDownload(retries = 2) {
 *     pageUrls.forEachIndexed { index, url ->
 *         item("page-$index", url, referer = chapterUrl, rateLimitBucket = "cdn")
 *     }
 * }
 * ```
 */
data class BulkDownload(
    override val id: String,
    val items: List<DownloadItem>,
    val retries: Int = 2,
    val retryDelayMs: Long = 1000,
    override val timeout: Long? = null
) : Instruction()

/**
 * A single item in a bulk download operation.
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val rateLimitBucket: String? = null
)

// ============================================================================
// Extraction Specification
// ============================================================================

/**
 * Specification for extracting structured data from a document.
 *
 * Contains a map of field names to their extraction specifications.
 */
data class ExtractionSpec(
    val fields: Map<String, FieldSpec>
)

/**
 * Specification for extracting a single field from a document.
 */
sealed class FieldSpec {
    /**
     * Extract a single text value from the first matching element.
     *
     * @param selector CSS selector to match
     * @param attribute Optional attribute to extract (default: text content)
     */
    data class Text(
        val selector: String,
        val attribute: String? = null
    ) : FieldSpec()

    /**
     * Extract text from the href attribute and resolve relative URLs.
     *
     * @param selector CSS selector to match (should select an anchor element)
     */
    data class Href(
        val selector: String
    ) : FieldSpec()

    /**
     * Extract text from the src attribute and resolve relative URLs.
     *
     * @param selector CSS selector to match (should select an img element)
     */
    data class Src(
        val selector: String
    ) : FieldSpec()

    /**
     * Extract a list of text values from all matching elements.
     *
     * @param selector CSS selector to match
     * @param attribute Optional attribute to extract (default: text content)
     */
    data class TextList(
        val selector: String,
        val attribute: String? = null
    ) : FieldSpec()

    /**
     * Extract an object from the first matching element.
     *
     * @param selector CSS selector to match
     * @param fields Nested field specifications
     */
    data class Nested(
        val selector: String,
        val fields: Map<String, FieldSpec>
    ) : FieldSpec()

    /**
     * Extract a list of objects from all matching elements.
     *
     * @param itemSelector CSS selector to match items
     * @param fields Nested field specifications for each item
     */
    data class NestedList(
        val itemSelector: String,
        val fields: Map<String, FieldSpec>
    ) : FieldSpec()
}
