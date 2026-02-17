package dev.koenv.chaptervault.orchestration.execution

import dev.koenv.chaptervault.core.browser.BrowserPool
import dev.koenv.chaptervault.core.browser.BrowserSession
import dev.koenv.chaptervault.core.browser.PageLoadState
import dev.koenv.chaptervault.core.dom.Document
import dev.koenv.chaptervault.core.dom.Element
import dev.koenv.chaptervault.core.execution.*
import dev.koenv.chaptervault.core.fetch.FetchClient
import dev.koenv.chaptervault.core.fetch.FetchException
import dev.koenv.chaptervault.core.fetch.FetchOptions
import dev.koenv.chaptervault.core.fetch.RequestBody
import dev.koenv.chaptervault.core.fetch.SessionFetchClient
import dev.koenv.chaptervault.orchestration.dom.JsoupDocument
import dev.koenv.chaptervault.orchestration.ratelimit.SiteRateLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Local executor implementation using FetchClient and BrowserPool.
 *
 * This executor runs instructions directly on the local machine,
 * using the managed HTTP client and browser pool.
 */
class LocalExecutor(
    private val fetchClient: FetchClient,
    private val browserPool: BrowserPool? = null,
    private val siteRateLimiter: SiteRateLimiter? = null
) : Executor {

    private val logger = LoggerFactory.getLogger(LocalExecutor::class.java)

    override suspend fun execute(instruction: Instruction, context: ExecutionContext): ExecutionResult {
        logger.debug("Executing instruction: {} ({})", instruction.id, instruction::class.simpleName)

        return try {
            when (instruction) {
                // HTTP Instructions
                is FetchHtml -> executeFetchHtml(instruction, context)
                is FetchJson -> executeFetchJson(instruction, context)
                is FetchBytes -> executeFetchBytes(instruction, context)
                is PostForm -> executePostForm(instruction, context)
                is PostJson -> executePostJson(instruction, context)

                // Browser Instructions
                is BrowserNavigate -> executeBrowserNavigate(instruction, context)
                is BrowserWaitForSelector -> executeBrowserWaitForSelector(instruction, context)
                is BrowserClick -> executeBrowserClick(instruction, context)
                is BrowserFill -> executeBrowserFill(instruction, context)
                is BrowserType -> executeBrowserType(instruction, context)
                is BrowserQueryAll -> executeBrowserQueryAll(instruction, context)
                is BrowserQueryFirst -> executeBrowserQueryFirst(instruction, context)
                is BrowserEvaluate -> executeBrowserEvaluate(instruction, context)
                is BrowserGetContent -> executeBrowserGetContent(instruction, context)
                is BrowserGetUrl -> executeBrowserGetUrl(instruction, context)
                is BrowserScroll -> executeBrowserScroll(instruction, context)
                is BrowserScrollToBottom -> executeBrowserScrollToBottom(instruction, context)
                is BrowserDownloadFile -> executeBrowserDownloadFile(instruction, context)
                is BrowserGetCookies -> executeBrowserGetCookies(instruction, context)
                is BrowserSetCookies -> executeBrowserSetCookies(instruction, context)

                // Declarative Extraction Instructions
                is FetchDocument -> executeFetchDocument(instruction, context)
                is ExtractData -> executeExtractData(instruction, context)
                is BulkDownload -> executeBulkDownload(instruction, context)

                // Composite Instructions
                is Sequence -> executeSequence(instruction, context)
                is Parallel -> executeParallel(instruction, context)
                is Conditional -> executeConditional(instruction, context)
                is Retry -> executeRetry(instruction, context)
            }
        } catch (e: Exception) {
            logger.error("Instruction {} failed: {}", instruction.id, e.message)
            ActionResult.failure(instruction.id, e.message ?: "Unknown error")
        }
    }

    override fun supports(instruction: Instruction): Boolean {
        return when (instruction) {
            is BrowserNavigate, is BrowserWaitForSelector, is BrowserClick,
            is BrowserFill, is BrowserType, is BrowserQueryAll, is BrowserQueryFirst,
            is BrowserEvaluate, is BrowserGetContent, is BrowserGetUrl,
            is BrowserScroll, is BrowserScrollToBottom, is BrowserDownloadFile,
            is BrowserGetCookies, is BrowserSetCookies -> browserPool != null

            else -> true
        }
    }

    override fun getInfo(): ExecutorInfo {
        return ExecutorInfo(
            name = "LocalExecutor",
            type = ExecutorType.LOCAL,
            supportsBrowser = browserPool != null,
            supportsParallel = true,
            maxConcurrency = 10,
            isRemote = false
        )
    }

    override fun close() {
        // Resources are managed externally (FetchClient, BrowserPool)
    }

    // ========================================================================
    // Site Rate Limiting
    // ========================================================================

    /**
     * Execute an HTTP request within the site rate limiter.
     * The URL is used for host-based bucket resolution. The optional bucket tag
     * overrides host-based bucketing with a named bucket from the connector's config.
     */
    private suspend fun <T> withSiteRateLimit(
        url: String,
        scope: RateLimitScope,
        bucketTag: String?,
        context: ExecutionContext,
        block: suspend () -> T
    ): T {
        if (scope != RateLimitScope.SITE && scope != RateLimitScope.CONNECTOR_AND_SITE) {
            return block()
        }
        val limiter = siteRateLimiter ?: return block()
        return limiter.withRateLimit(url, context.connectorName, bucketTag, block)
    }

    /**
     * Check a response for 429 status and report to the rate limiter for adaptive backoff.
     * Also reports successful responses to allow backoff recovery.
     */
    private suspend fun handleRateLimitResponse(
        url: String,
        bucketTag: String?,
        context: ExecutionContext,
        statusCode: Int,
        headers: Map<String, List<String>>
    ) {
        val limiter = siteRateLimiter ?: return
        if (statusCode == 429) {
            val retryAfter = headers["Retry-After"]?.firstOrNull()
                ?: headers["retry-after"]?.firstOrNull()
            val retryAfterSeconds = retryAfter?.toLongOrNull()
            limiter.report429(url, context.connectorName, bucketTag, retryAfterSeconds)
        } else if (statusCode in 200..299) {
            limiter.reportSuccess(url, context.connectorName, bucketTag)
        }
    }

    // ========================================================================
    // HTTP Instruction Execution
    // ========================================================================

    private suspend fun executeFetchHtml(instruction: FetchHtml, context: ExecutionContext): HtmlResult {
        val session = getHttpSession(context)
        val options = buildFetchOptions(instruction.headers, instruction.referer, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.get(instruction.url, options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        return if (response.isSuccess) {
            HtmlResult.success(instruction.id, response.body, response.url, response.statusCode)
        } else {
            HtmlResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }
    }

    private suspend fun executeFetchJson(instruction: FetchJson, context: ExecutionContext): JsonResult {
        val session = getHttpSession(context)
        val headers = instruction.headers.toMutableMap()
        headers["Accept"] = "application/json"
        val options = buildFetchOptions(headers, instruction.referer, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.get(instruction.url, options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        return if (response.isSuccess) {
            JsonResult.success(instruction.id, response.body, response.url, response.statusCode)
        } else {
            JsonResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }
    }

    private suspend fun executeFetchBytes(instruction: FetchBytes, context: ExecutionContext): BytesResult {
        val session = getHttpSession(context)
        val options = buildFetchOptions(instruction.headers, instruction.referer, instruction.timeout, context)

        return try {
            val bytes = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
                session.downloadBytes(instruction.url, options)
            }
            siteRateLimiter?.reportSuccess(instruction.url, context.connectorName, instruction.rateLimitBucket)
            BytesResult.success(instruction.id, bytes, guessMimeType(instruction.url))
        } catch (e: FetchException) {
            if (e.statusCode == 429) {
                siteRateLimiter?.report429(instruction.url, context.connectorName, instruction.rateLimitBucket, null)
            }
            BytesResult.failure(instruction.id, e.message ?: "Download failed")
        } catch (e: Exception) {
            BytesResult.failure(instruction.id, e.message ?: "Download failed")
        }
    }

    private suspend fun executePostForm(instruction: PostForm, context: ExecutionContext): HtmlResult {
        val session = getHttpSession(context)
        val options = buildFetchOptions(instruction.headers, null, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.postForm(instruction.url, instruction.formData, options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        return if (response.isSuccess) {
            HtmlResult.success(instruction.id, response.body, response.url, response.statusCode)
        } else {
            HtmlResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }
    }

    private suspend fun executePostJson(instruction: PostJson, context: ExecutionContext): JsonResult {
        val session = getHttpSession(context)
        val headers = instruction.headers.toMutableMap()
        headers["Content-Type"] = "application/json"
        val options = buildFetchOptions(headers, null, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.post(instruction.url, RequestBody.json(instruction.jsonBody), options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        return if (response.isSuccess) {
            JsonResult.success(instruction.id, response.body, response.url, response.statusCode)
        } else {
            JsonResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }
    }

    // ========================================================================
    // Declarative Extraction Instruction Execution
    // ========================================================================

    private suspend fun executeFetchDocument(instruction: FetchDocument, context: ExecutionContext): DocumentResult {
        val session = getHttpSession(context)
        val options = buildFetchOptions(instruction.headers, instruction.referer, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.get(instruction.url, options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        return if (response.isSuccess) {
            val document = JsoupDocument.parse(response.body, response.url)
            DocumentResult.success(instruction.id, document, response.statusCode)
        } else {
            DocumentResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }
    }

    private suspend fun executeExtractData(instruction: ExtractData, context: ExecutionContext): ExtractedDataResult {
        val session = getHttpSession(context)
        val options = buildFetchOptions(instruction.headers, instruction.referer, instruction.timeout, context)

        val response = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.get(instruction.url, options)
        }
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, response.statusCode, response.headers)
        if (!response.isSuccess) {
            return ExtractedDataResult.failure(instruction.id, "HTTP ${response.statusCode}", response.statusCode)
        }

        val document = JsoupDocument.parse(response.body, response.url)
        val data = extractFromDocument(document, instruction.spec)

        return ExtractedDataResult.success(instruction.id, data, response.url, response.statusCode)
    }

    private suspend fun executeBulkDownload(instruction: BulkDownload, context: ExecutionContext): BulkDownloadResult {
        if (instruction.items.isEmpty()) {
            return BulkDownloadResult.success(instruction.id, emptyMap())
        }

        val session = getHttpSession(context)
        val semaphore = Semaphore(instruction.maxConcurrency)
        val results = mutableMapOf<String, DownloadItemResult>()

        coroutineScope {
            val deferred = instruction.items.map { item ->
                async {
                    semaphore.withPermit {
                        downloadItemWithRetry(session, item, instruction.retries, instruction.retryDelayMs, context)
                    }
                }
            }

            deferred.awaitAll().forEach { result ->
                results[result.id] = result
            }
        }

        return BulkDownloadResult.partialSuccess(instruction.id, results)
    }

    private suspend fun downloadItemWithRetry(
        session: SessionFetchClient,
        item: DownloadItem,
        maxRetries: Int,
        retryDelayMs: Long,
        context: ExecutionContext
    ): DownloadItemResult {
        var lastError: String? = null
        var currentDelay = retryDelayMs

        repeat(maxRetries + 1) { attempt ->
            try {
                val options = buildFetchOptions(item.headers, item.referer, null, context)
                val bytes = withSiteRateLimit(item.url, item.rateLimitScope, item.rateLimitBucket, context) {
                    session.downloadBytes(item.url, options)
                }
                siteRateLimiter?.reportSuccess(item.url, context.connectorName, item.rateLimitBucket)
                return DownloadItemResult.success(item.id, bytes, guessMimeType(item.url))
            } catch (e: FetchException) {
                lastError = e.message ?: "Download failed"
                if (e.statusCode == 429) {
                    siteRateLimiter?.report429(item.url, context.connectorName, item.rateLimitBucket, null)
                }
                if (attempt < maxRetries) {
                    logger.debug("Retry {} for item {}: {}", attempt + 1, item.id, lastError)
                    delay(currentDelay)
                    currentDelay *= 2
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Download failed"
                if (attempt < maxRetries) {
                    logger.debug("Retry {} for item {}: {}", attempt + 1, item.id, lastError)
                    delay(currentDelay)
                    currentDelay *= 2
                }
            }
        }

        return DownloadItemResult.failure(item.id, lastError ?: "Download failed after retries")
    }

    private fun extractFromDocument(document: Document, spec: ExtractionSpec): Map<String, Any?> {
        return spec.fields.mapValues { (_, fieldSpec) ->
            extractField(document, fieldSpec)
        }
    }

    private fun extractField(context: Any, fieldSpec: FieldSpec): Any? {
        return when (fieldSpec) {
            is FieldSpec.Text -> extractText(context, fieldSpec.selector, fieldSpec.attribute)
            is FieldSpec.Href -> extractHref(context, fieldSpec.selector)
            is FieldSpec.Src -> extractSrc(context, fieldSpec.selector)
            is FieldSpec.TextList -> extractTextList(context, fieldSpec.selector, fieldSpec.attribute)
            is FieldSpec.Nested -> extractNested(context, fieldSpec.selector, fieldSpec.fields)
            is FieldSpec.NestedList -> extractNestedList(context, fieldSpec.itemSelector, fieldSpec.fields)
        }
    }

    private fun extractText(context: Any, selector: String, attribute: String?): String? {
        val element = selectFirst(context, selector) ?: return null
        return if (attribute != null) {
            element.attr(attribute)
        } else {
            element.textContent
        }
    }

    private fun extractHref(context: Any, selector: String): String? {
        val element = selectFirst(context, selector) ?: return null
        return element.href()
    }

    private fun extractSrc(context: Any, selector: String): String? {
        val element = selectFirst(context, selector) ?: return null
        return element.src()
    }

    private fun extractTextList(context: Any, selector: String, attribute: String?): List<String> {
        val elements = select(context, selector)
        return elements.mapNotNull { element ->
            if (attribute != null) {
                element.attr(attribute)
            } else {
                element.textContent
            }
        }
    }

    private fun extractNested(context: Any, selector: String, fields: Map<String, FieldSpec>): Map<String, Any?>? {
        val element = selectFirst(context, selector) ?: return null
        return fields.mapValues { (_, fieldSpec) ->
            extractField(element, fieldSpec)
        }
    }

    private fun extractNestedList(context: Any, itemSelector: String, fields: Map<String, FieldSpec>): List<Map<String, Any?>> {
        val elements = select(context, itemSelector)
        return elements.map { element ->
            fields.mapValues { (_, fieldSpec) ->
                extractField(element, fieldSpec)
            }
        }
    }

    private fun selectFirst(context: Any, selector: String): Element? {
        return when (context) {
            is Document -> context.selectFirst(selector)
            is Element -> context.selectFirst(selector)
            else -> null
        }
    }

    private fun select(context: Any, selector: String): List<Element> {
        return when (context) {
            is Document -> context.select(selector)
            is Element -> context.select(selector)
            else -> emptyList()
        }
    }

    // ========================================================================
    // Browser Instruction Execution
    // ========================================================================

    private suspend fun getBrowserSession(context: ExecutionContext): BrowserSession {
        val pool = browserPool ?: throw IllegalStateException("Browser pool not available")
        val sessionId = context.sessionId ?: context.connectorName ?: "default"
        return pool.getConnectorSession(sessionId)
    }

    private suspend fun executeBrowserNavigate(instruction: BrowserNavigate, context: ExecutionContext): HtmlResult {
        val session = getBrowserSession(context)
        val waitUntil = when (instruction.waitUntil) {
            WaitCondition.LOAD -> PageLoadState.LOAD
            WaitCondition.DOM_CONTENT -> PageLoadState.DOM_CONTENT
            WaitCondition.NETWORK_IDLE -> PageLoadState.NETWORK_IDLE
        }

        val content = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.navigate(instruction.url, waitUntil)
        }
        val statusCode = content.statusCode ?: 200
        handleRateLimitResponse(instruction.url, instruction.rateLimitBucket, context, statusCode, emptyMap())
        return HtmlResult.success(instruction.id, content.html, content.url, statusCode)
    }

    private suspend fun executeBrowserWaitForSelector(instruction: BrowserWaitForSelector, context: ExecutionContext): BooleanResult {
        val session = getBrowserSession(context)
        val found = session.waitForSelector(instruction.selector, instruction.timeout ?: 30_000)
        return BooleanResult.success(instruction.id, found)
    }

    private suspend fun executeBrowserClick(instruction: BrowserClick, context: ExecutionContext): ActionResult {
        val session = getBrowserSession(context)
        session.click(instruction.selector)
        return ActionResult.success(instruction.id)
    }

    private suspend fun executeBrowserFill(instruction: BrowserFill, context: ExecutionContext): ActionResult {
        val session = getBrowserSession(context)
        session.fill(instruction.selector, instruction.value)
        return ActionResult.success(instruction.id)
    }

    private suspend fun executeBrowserType(instruction: BrowserType, context: ExecutionContext): ActionResult {
        val session = getBrowserSession(context)
        session.type(instruction.selector, instruction.text, instruction.delayMs)
        return ActionResult.success(instruction.id)
    }

    private suspend fun executeBrowserQueryAll(instruction: BrowserQueryAll, context: ExecutionContext): ElementsResult {
        val session = getBrowserSession(context)
        val elements = session.queryAll(instruction.selector)
        val elementData = elements.map { el ->
            ElementData(
                tagName = el.tagName,
                textContent = el.textContent,
                innerHTML = el.innerHTML,
                attributes = el.attributes,
                isVisible = el.isVisible
            )
        }
        return ElementsResult.success(instruction.id, elementData)
    }

    private suspend fun executeBrowserQueryFirst(instruction: BrowserQueryFirst, context: ExecutionContext): ElementResult {
        val session = getBrowserSession(context)
        val element = session.queryFirst(instruction.selector)
        return if (element != null) {
            ElementResult.success(instruction.id, ElementData(
                tagName = element.tagName,
                textContent = element.textContent,
                innerHTML = element.innerHTML,
                attributes = element.attributes,
                isVisible = element.isVisible
            ))
        } else {
            ElementResult.notFound(instruction.id)
        }
    }

    private suspend fun executeBrowserEvaluate(instruction: BrowserEvaluate, context: ExecutionContext): StringResult {
        val session = getBrowserSession(context)
        val result = session.evaluate(instruction.script)
        return StringResult.success(instruction.id, result ?: "")
    }

    private suspend fun executeBrowserGetContent(instruction: BrowserGetContent, context: ExecutionContext): HtmlResult {
        val session = getBrowserSession(context)
        val content = session.getContent()
        val url = session.getCurrentUrl()
        return HtmlResult.success(instruction.id, content, url)
    }

    private suspend fun executeBrowserGetUrl(instruction: BrowserGetUrl, context: ExecutionContext): StringResult {
        val session = getBrowserSession(context)
        val url = session.getCurrentUrl()
        return StringResult.success(instruction.id, url)
    }

    private suspend fun executeBrowserScroll(instruction: BrowserScroll, context: ExecutionContext): ActionResult {
        val session = getBrowserSession(context)
        session.scroll(instruction.x, instruction.y)
        return ActionResult.success(instruction.id)
    }

    private suspend fun executeBrowserScrollToBottom(instruction: BrowserScrollToBottom, context: ExecutionContext): IntResult {
        val session = getBrowserSession(context)
        val scrolls = session.scrollToBottom(instruction.maxScrolls, instruction.delayBetweenScrollsMs)
        return IntResult.success(instruction.id, scrolls)
    }

    private suspend fun executeBrowserDownloadFile(instruction: BrowserDownloadFile, context: ExecutionContext): BytesResult {
        val session = getBrowserSession(context)
        val bytes = withSiteRateLimit(instruction.url, instruction.rateLimitScope, instruction.rateLimitBucket, context) {
            session.downloadFile(instruction.url, instruction.referer)
        }
        siteRateLimiter?.reportSuccess(instruction.url, context.connectorName, instruction.rateLimitBucket)
        return BytesResult.success(instruction.id, bytes, guessMimeType(instruction.url))
    }

    private suspend fun executeBrowserGetCookies(instruction: BrowserGetCookies, context: ExecutionContext): CookiesResult {
        val session = getBrowserSession(context)
        val cookies = session.getCookies().map { cookie ->
            CookieData(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                sameSite = cookie.sameSite,
                expires = cookie.expires
            )
        }
        return CookiesResult.success(instruction.id, cookies)
    }

    private suspend fun executeBrowserSetCookies(instruction: BrowserSetCookies, context: ExecutionContext): ActionResult {
        val session = getBrowserSession(context)
        val browserCookies = instruction.cookies.map { cookie ->
            dev.koenv.chaptervault.core.browser.BrowserCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                sameSite = cookie.sameSite,
                expires = cookie.expires
            )
        }
        session.setCookies(browserCookies)
        return ActionResult.success(instruction.id)
    }

    // ========================================================================
    // Composite Instruction Execution
    // ========================================================================

    private suspend fun executeSequence(instruction: Sequence, context: ExecutionContext): CompositeResult {
        val results = mutableMapOf<String, ExecutionResult>()
        for (child in instruction.instructions) {
            val result = execute(child, context)
            results[child.id] = result
            if (!result.success) {
                return CompositeResult.failure(instruction.id, "Sequence failed at ${child.id}", results)
            }
        }
        return CompositeResult.success(instruction.id, results)
    }

    private suspend fun executeParallel(instruction: Parallel, context: ExecutionContext): CompositeResult {
        return coroutineScope {
            val deferred = instruction.instructions.map { child ->
                async { child.id to execute(child, context) }
            }
            val results = deferred.awaitAll().toMap()
            val allSuccess = results.values.all { it.success }
            if (allSuccess) {
                CompositeResult.success(instruction.id, results)
            } else {
                CompositeResult.failure(instruction.id, "Some parallel instructions failed", results)
            }
        }
    }

    private suspend fun executeConditional(instruction: Conditional, context: ExecutionContext): ExecutionResult {
        // The condition references a previous instruction ID
        // This requires the condition to be evaluated against previous results
        // For now, we'll execute the 'then' branch by default
        return execute(instruction.thenInstruction, context)
    }

    private suspend fun executeRetry(instruction: Retry, context: ExecutionContext): ExecutionResult {
        var lastResult: ExecutionResult? = null
        var currentDelay = instruction.delayMs

        repeat(instruction.maxRetries + 1) { attempt ->
            val result = execute(instruction.instruction, context)
            lastResult = result
            if (result.success) {
                return result
            }

            if (attempt < instruction.maxRetries) {
                logger.debug("Retry {} for instruction {}", attempt + 1, instruction.instruction.id)
                delay(currentDelay)
                if (instruction.exponentialBackoff) {
                    currentDelay *= 2
                }
            }
        }

        return lastResult ?: ActionResult.failure(instruction.id, "Retry exhausted")
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun getHttpSession(context: ExecutionContext): SessionFetchClient {
        val sessionId = context.sessionId ?: context.connectorName ?: "default"
        return fetchClient.getConnectorSession(sessionId)
    }

    private fun buildFetchOptions(
        headers: Map<String, String>,
        referer: String?,
        timeout: Long?,
        context: ExecutionContext
    ): FetchOptions {
        val allHeaders = context.defaultHeaders.toMutableMap()
        allHeaders.putAll(headers)

        return FetchOptions(
            headers = allHeaders,
            referer = referer,
            timeout = timeout ?: context.timeout ?: 30_000
        )
    }

    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            ".png" in lower -> "image/png"
            ".gif" in lower -> "image/gif"
            ".webp" in lower -> "image/webp"
            ".svg" in lower -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }
}
