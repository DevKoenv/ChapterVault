package dev.koenv.chaptervault.core.execution

import java.util.UUID

/**
 * DSL for building execution plans.
 *
 * Example usage:
 * ```kotlin
 * val plan = executionPlan {
 *     val searchPage = fetchHtml("https://example.com/search?q=test")
 *
 *     // Or with browser
 *     browser {
 *         navigate("https://example.com/search")
 *         fill("input[name=q]", "test")
 *         click("button[type=submit]")
 *         waitForSelector(".results")
 *         val elements = queryAll(".result-item")
 *     }
 * }
 *
 * val results = executor.execute(plan)
 * ```
 */
@DslMarker
annotation class PlanDsl

/**
 * Entry point for building an execution plan.
 */
fun executionPlan(block: PlanBuilder.() -> Unit): ExecutionPlan {
    val builder = PlanBuilder()
    builder.block()
    return builder.build()
}

/**
 * An execution plan containing instructions and metadata.
 */
data class ExecutionPlan(
    val id: String,
    val instructions: List<Instruction>,
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Get all instruction IDs in this plan.
     */
    val instructionIds: List<String> get() = instructions.map { it.id }
}

/**
 * Builder for creating execution plans with a fluent DSL.
 */
@PlanDsl
class PlanBuilder {
    private val instructions = mutableListOf<Instruction>()
    private val metadata = mutableMapOf<String, Any>()
    private var idCounter = 0

    /**
     * Generate a unique ID for an instruction.
     */
    private fun nextId(prefix: String = "instr"): String = "$prefix-${idCounter++}"

    // ========================================================================
    // HTTP Instructions
    // ========================================================================

    /**
     * Fetch HTML content from a URL.
     */
    fun fetchHtml(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("html")
    ): InstructionRef<HtmlResult> {
        val instruction = FetchHtml(id, url, headers, referer, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * Fetch JSON content from a URL.
     */
    fun fetchJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("json")
    ): InstructionRef<JsonResult> {
        val instruction = FetchJson(id, url, headers, referer, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * Fetch binary content from a URL.
     */
    fun fetchBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("bytes")
    ): InstructionRef<BytesResult> {
        val instruction = FetchBytes(id, url, headers, referer, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * POST form data to a URL.
     */
    fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("form")
    ): InstructionRef<HtmlResult> {
        val instruction = PostForm(id, url, formData, headers, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * POST JSON data to a URL.
     */
    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("post")
    ): InstructionRef<JsonResult> {
        val instruction = PostJson(id, url, jsonBody, headers, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    // ========================================================================
    // Declarative Extraction Instructions
    // ========================================================================

    /**
     * Fetch a URL and return a parsed Document.
     */
    fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("doc")
    ): InstructionRef<DocumentResult> {
        val instruction = FetchDocument(id, url, headers, referer, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * Fetch a URL and extract structured data using a declarative specification.
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
    fun extractData(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("extract"),
        spec: ExtractionSpecBuilder.() -> Unit
    ): InstructionRef<ExtractedDataResult> {
        val specBuilder = ExtractionSpecBuilder()
        specBuilder.spec()
        val instruction = ExtractData(id, url, specBuilder.build(), headers, referer, timeout, rateLimitBucket)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    /**
     * Download multiple files with retry logic.
     *
     * Concurrency is governed entirely by the rate-limit bucket set on each [item][BulkDownloadBuilder.item]
     * via `rateLimitBucket`. Set [maxConcurrent][dev.koenv.chaptervault.core.ratelimit.RateLimitConfig.maxConcurrent]
     * on the corresponding bucket in the connector's [siteRateLimits][dev.koenv.chaptervault.core.connector.ConnectorConfig.siteRateLimits]
     * config to cap how many downloads are in-flight at once.
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
    fun bulkDownload(
        retries: Int = 2,
        retryDelayMs: Long = 1000,
        timeout: Long? = null,
        id: String = nextId("bulk"),
        items: BulkDownloadBuilder.() -> Unit
    ): InstructionRef<BulkDownloadResult> {
        val itemsBuilder = BulkDownloadBuilder()
        itemsBuilder.items()
        val instruction = BulkDownload(id, itemsBuilder.build(), retries, retryDelayMs, timeout)
        instructions.add(instruction)
        return InstructionRef(id)
    }

    // ========================================================================
    // Browser Instructions (via nested builder)
    // ========================================================================

    /**
     * Execute browser instructions.
     */
    fun browser(block: BrowserPlanBuilder.() -> Unit): List<Instruction> {
        val browserBuilder = BrowserPlanBuilder(this::nextId)
        browserBuilder.block()
        val browserInstructions = browserBuilder.build()
        instructions.addAll(browserInstructions)
        return browserInstructions
    }

    // ========================================================================
    // Control Flow
    // ========================================================================

    /**
     * Execute instructions in parallel.
     */
    fun parallel(block: PlanBuilder.() -> Unit): InstructionRef<CompositeResult> {
        val parallelBuilder = PlanBuilder()
        parallelBuilder.block()
        val parallelInstructions = parallelBuilder.instructions.toList()

        val id = nextId("parallel")
        instructions.add(Parallel(id, parallelInstructions))
        return InstructionRef(id)
    }

    /**
     * Wrap an instruction with retry logic.
     */
    fun <T : ExecutionResult> retry(
        ref: InstructionRef<T>,
        maxRetries: Int = 3,
        delayMs: Long = 1000,
        exponentialBackoff: Boolean = true
    ): InstructionRef<T> {
        // Find and remove the original instruction
        val originalIndex = instructions.indexOfFirst { it.id == ref.id }
        if (originalIndex >= 0) {
            val original = instructions.removeAt(originalIndex)
            val retryId = nextId("retry")
            instructions.add(originalIndex, Retry(retryId, original, maxRetries, delayMs, exponentialBackoff))
            return InstructionRef(retryId)
        }
        return ref
    }

    // ========================================================================
    // Metadata
    // ========================================================================

    /**
     * Add metadata to the plan.
     */
    fun metadata(key: String, value: Any) {
        metadata[key] = value
    }

    /**
     * Build the execution plan.
     */
    fun build(): ExecutionPlan {
        return ExecutionPlan(
            id = UUID.randomUUID().toString(),
            instructions = instructions.toList(),
            metadata = metadata.toMap()
        )
    }
}

/**
 * Builder for browser-specific instructions.
 */
@PlanDsl
class BrowserPlanBuilder(private val nextId: (String) -> String) {
    private val instructions = mutableListOf<Instruction>()

    /**
     * Navigate to a URL.
     */
    fun navigate(
        url: String,
        waitUntil: WaitCondition = WaitCondition.NETWORK_IDLE,
        timeout: Long? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("nav")
    ): InstructionRef<HtmlResult> {
        instructions.add(BrowserNavigate(id, url, waitUntil, timeout, rateLimitBucket))
        return InstructionRef(id)
    }

    /**
     * Wait for a selector to appear.
     */
    fun waitForSelector(
        selector: String,
        timeout: Long? = 30_000,
        id: String = nextId("wait")
    ): InstructionRef<BooleanResult> {
        instructions.add(BrowserWaitForSelector(id, selector, timeout))
        return InstructionRef(id)
    }

    /**
     * Click an element.
     */
    fun click(
        selector: String,
        id: String = nextId("click")
    ): InstructionRef<ActionResult> {
        instructions.add(BrowserClick(id, selector))
        return InstructionRef(id)
    }

    /**
     * Fill an input field.
     */
    fun fill(
        selector: String,
        value: String,
        id: String = nextId("fill")
    ): InstructionRef<ActionResult> {
        instructions.add(BrowserFill(id, selector, value))
        return InstructionRef(id)
    }

    /**
     * Type text with keystroke events.
     */
    fun type(
        selector: String,
        text: String,
        delayMs: Long = 0,
        id: String = nextId("type")
    ): InstructionRef<ActionResult> {
        instructions.add(BrowserType(id, selector, text, delayMs))
        return InstructionRef(id)
    }

    /**
     * Query all elements matching a selector.
     */
    fun queryAll(
        selector: String,
        id: String = nextId("query")
    ): InstructionRef<ElementsResult> {
        instructions.add(BrowserQueryAll(id, selector))
        return InstructionRef(id)
    }

    /**
     * Query first element matching a selector.
     */
    fun queryFirst(
        selector: String,
        id: String = nextId("query")
    ): InstructionRef<ElementResult> {
        instructions.add(BrowserQueryFirst(id, selector))
        return InstructionRef(id)
    }

    /**
     * Execute JavaScript.
     */
    fun evaluate(
        script: String,
        id: String = nextId("eval")
    ): InstructionRef<StringResult> {
        instructions.add(BrowserEvaluate(id, script))
        return InstructionRef(id)
    }

    /**
     * Get current page content.
     */
    fun getContent(
        id: String = nextId("content")
    ): InstructionRef<HtmlResult> {
        instructions.add(BrowserGetContent(id))
        return InstructionRef(id)
    }

    /**
     * Get current URL.
     */
    fun getUrl(
        id: String = nextId("url")
    ): InstructionRef<StringResult> {
        instructions.add(BrowserGetUrl(id))
        return InstructionRef(id)
    }

    /**
     * Scroll the page.
     */
    fun scroll(
        x: Int = 0,
        y: Int = 0,
        id: String = nextId("scroll")
    ): InstructionRef<ActionResult> {
        instructions.add(BrowserScroll(id, x, y))
        return InstructionRef(id)
    }

    /**
     * Scroll to bottom for infinite scroll pages.
     */
    fun scrollToBottom(
        maxScrolls: Int = 10,
        delayMs: Long = 500,
        id: String = nextId("scrollBottom")
    ): InstructionRef<IntResult> {
        instructions.add(BrowserScrollToBottom(id, maxScrolls, delayMs))
        return InstructionRef(id)
    }

    /**
     * Download file using browser context.
     */
    fun downloadFile(
        url: String,
        referer: String? = null,
        rateLimitBucket: String? = null,
        id: String = nextId("download")
    ): InstructionRef<BytesResult> {
        instructions.add(BrowserDownloadFile(id, url, referer, rateLimitBucket))
        return InstructionRef(id)
    }

    /**
     * Get cookies.
     */
    fun getCookies(
        id: String = nextId("cookies")
    ): InstructionRef<CookiesResult> {
        instructions.add(BrowserGetCookies(id))
        return InstructionRef(id)
    }

    /**
     * Set cookies.
     */
    fun setCookies(
        cookies: List<CookieData>,
        id: String = nextId("setCookies")
    ): InstructionRef<ActionResult> {
        instructions.add(BrowserSetCookies(id, cookies))
        return InstructionRef(id)
    }

    /**
     * Build the browser instructions.
     */
    fun build(): List<Instruction> = instructions.toList()
}

/**
 * Reference to an instruction, used to retrieve results.
 *
 * @param T The expected result type
 */
data class InstructionRef<T : ExecutionResult>(val id: String) {
    /**
     * Get the result from a results map.
     */
    @Suppress("UNCHECKED_CAST")
    fun getFrom(results: Map<String, ExecutionResult>): T? {
        return results[id] as? T
    }

    /**
     * Get the result from a composite result.
     */
    @Suppress("UNCHECKED_CAST")
    fun getFrom(result: CompositeResult): T? {
        return result.results[id] as? T
    }
}

/**
 * Builder for creating extraction specifications.
 *
 * This provides a fluent DSL for defining what data to extract from HTML:
 *
 * ```kotlin
 * extractData(url) {
 *     text("title", "h1")
 *     textList("tags", ".tag")
 *     nestedList("results", ".search-result") {
 *         href("url", "a[href]")
 *         text("title", ".title")
 *         src("cover", "img")
 *     }
 * }
 * ```
 */
@PlanDsl
class ExtractionSpecBuilder {
    private val fields = mutableMapOf<String, FieldSpec>()

    /**
     * Extract a single text value from the first matching element.
     *
     * @param name Field name in the result
     * @param selector CSS selector to match
     * @param attribute Optional attribute to extract (default: text content)
     */
    fun text(name: String, selector: String, attribute: String? = null) {
        fields[name] = FieldSpec.Text(selector, attribute)
    }

    /**
     * Extract the href attribute from the first matching anchor element.
     * Relative URLs are resolved against the document's base URL.
     *
     * @param name Field name in the result
     * @param selector CSS selector to match
     */
    fun href(name: String, selector: String) {
        fields[name] = FieldSpec.Href(selector)
    }

    /**
     * Extract the src attribute from the first matching element.
     * Relative URLs are resolved against the document's base URL.
     *
     * @param name Field name in the result
     * @param selector CSS selector to match
     */
    fun src(name: String, selector: String) {
        fields[name] = FieldSpec.Src(selector)
    }

    /**
     * Extract a list of text values from all matching elements.
     *
     * @param name Field name in the result
     * @param selector CSS selector to match
     * @param attribute Optional attribute to extract (default: text content)
     */
    fun textList(name: String, selector: String, attribute: String? = null) {
        fields[name] = FieldSpec.TextList(selector, attribute)
    }

    /**
     * Extract an object from the first matching element.
     *
     * @param name Field name in the result
     * @param selector CSS selector to match
     * @param spec Nested field specifications
     */
    fun nested(name: String, selector: String, spec: ExtractionSpecBuilder.() -> Unit) {
        val nestedBuilder = ExtractionSpecBuilder()
        nestedBuilder.spec()
        fields[name] = FieldSpec.Nested(selector, nestedBuilder.fields.toMap())
    }

    /**
     * Extract a list of objects from all matching elements.
     *
     * This is the most common pattern for extracting search results,
     * chapter lists, etc.
     *
     * @param name Field name in the result
     * @param itemSelector CSS selector to match items
     * @param spec Nested field specifications for each item
     */
    fun nestedList(name: String, itemSelector: String, spec: ExtractionSpecBuilder.() -> Unit) {
        val nestedBuilder = ExtractionSpecBuilder()
        nestedBuilder.spec()
        fields[name] = FieldSpec.NestedList(itemSelector, nestedBuilder.fields.toMap())
    }

    /**
     * Build the extraction specification.
     */
    fun build(): ExtractionSpec = ExtractionSpec(fields.toMap())
}

/**
 * Builder for bulk download items.
 *
 * ```kotlin
 * bulkDownload(retries = 2) {
 *     pageUrls.forEachIndexed { index, url ->
 *         item("page-$index", url, referer = chapterUrl, rateLimitBucket = "cdn")
 *     }
 * }
 * ```
 */
@PlanDsl
class BulkDownloadBuilder {
    private val items = mutableListOf<DownloadItem>()

    /**
     * Add an item to download.
     *
     * @param id Unique identifier for this item (used in results)
     * @param url URL to download
     * @param headers Additional headers for this request
     * @param referer Referer header value
     */
    fun item(
        id: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        rateLimitBucket: String? = null
    ) {
        items.add(DownloadItem(id, url, headers, referer, rateLimitBucket))
    }

    /**
     * Build the list of download items.
     */
    fun build(): List<DownloadItem> = items.toList()
}
