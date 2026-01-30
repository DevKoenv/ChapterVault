package dev.koenv.chaptervault.core.execution

/**
 * Result of executing an instruction.
 *
 * Results are typed based on the instruction type:
 * - FetchHtml -> HtmlResult
 * - FetchJson -> JsonResult
 * - FetchBytes -> BytesResult
 * - BrowserQueryAll -> ElementsResult
 * - etc.
 */
sealed class ExecutionResult {
    /**
     * Whether the execution was successful.
     */
    abstract val success: Boolean

    /**
     * Error message if execution failed.
     */
    abstract val error: String?

    /**
     * The instruction ID this result corresponds to.
     */
    abstract val instructionId: String
}

/**
 * Result containing HTML content.
 */
data class HtmlResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val html: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val headers: Map<String, List<String>> = emptyMap()
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, html: String, url: String, statusCode: Int = 200) =
            HtmlResult(instructionId, true, null, html, url, statusCode)

        fun failure(instructionId: String, error: String, statusCode: Int? = null) =
            HtmlResult(instructionId, false, error, null, null, statusCode)
    }
}

/**
 * Result containing JSON content.
 */
data class JsonResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val json: String? = null,
    val url: String? = null,
    val statusCode: Int? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, json: String, url: String, statusCode: Int = 200) =
            JsonResult(instructionId, true, null, json, url, statusCode)

        fun failure(instructionId: String, error: String, statusCode: Int? = null) =
            JsonResult(instructionId, false, error, null, null, statusCode)
    }
}

/**
 * Result containing binary data.
 */
data class BytesResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val size: Long? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, bytes: ByteArray, mimeType: String? = null) =
            BytesResult(instructionId, true, null, bytes, mimeType, bytes.size.toLong())

        fun failure(instructionId: String, error: String) =
            BytesResult(instructionId, false, error)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BytesResult) return false
        return instructionId == other.instructionId &&
            success == other.success &&
            error == other.error &&
            bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = instructionId.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result indicating simple success/failure (for actions like click, fill).
 */
data class ActionResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String) = ActionResult(instructionId, true)
        fun failure(instructionId: String, error: String) = ActionResult(instructionId, false, error)
    }
}

/**
 * Result containing a boolean value.
 */
data class BooleanResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val value: Boolean? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, value: Boolean) =
            BooleanResult(instructionId, true, null, value)

        fun failure(instructionId: String, error: String) =
            BooleanResult(instructionId, false, error)
    }
}

/**
 * Result containing a string value.
 */
data class StringResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val value: String? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, value: String) =
            StringResult(instructionId, true, null, value)

        fun failure(instructionId: String, error: String) =
            StringResult(instructionId, false, error)
    }
}

/**
 * Result containing an integer value.
 */
data class IntResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val value: Int? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, value: Int) =
            IntResult(instructionId, true, null, value)

        fun failure(instructionId: String, error: String) =
            IntResult(instructionId, false, error)
    }
}

/**
 * Result containing DOM element data.
 */
data class ElementResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val element: ElementData? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, element: ElementData) =
            ElementResult(instructionId, true, null, element)

        fun notFound(instructionId: String) =
            ElementResult(instructionId, true, null, null)

        fun failure(instructionId: String, error: String) =
            ElementResult(instructionId, false, error)
    }
}

/**
 * Result containing multiple DOM elements.
 */
data class ElementsResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val elements: List<ElementData> = emptyList()
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, elements: List<ElementData>) =
            ElementsResult(instructionId, true, null, elements)

        fun failure(instructionId: String, error: String) =
            ElementsResult(instructionId, false, error)
    }
}

/**
 * DOM element data extracted from browser.
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
    fun dataAttr(name: String): String? = attributes["data-$name"]
}

/**
 * Result containing cookies.
 */
data class CookiesResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val cookies: List<CookieData> = emptyList()
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, cookies: List<CookieData>) =
            CookiesResult(instructionId, true, null, cookies)

        fun failure(instructionId: String, error: String) =
            CookiesResult(instructionId, false, error)
    }
}

/**
 * Result containing multiple results (for Sequence/Parallel).
 */
data class CompositeResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val results: Map<String, ExecutionResult> = emptyMap()
) : ExecutionResult() {
    /**
     * Get a specific result by instruction ID.
     */
    inline fun <reified T : ExecutionResult> get(id: String): T? {
        return results[id] as? T
    }

    /**
     * Get all results in order (for Sequence).
     */
    fun asList(): List<ExecutionResult> = results.values.toList()

    companion object {
        fun success(instructionId: String, results: Map<String, ExecutionResult>) =
            CompositeResult(instructionId, true, null, results)

        fun failure(instructionId: String, error: String, partialResults: Map<String, ExecutionResult> = emptyMap()) =
            CompositeResult(instructionId, false, error, partialResults)
    }
}

// ============================================================================
// Declarative Extraction Results
// ============================================================================

/**
 * Result containing a parsed Document.
 *
 * Unlike HtmlResult which returns raw HTML, this returns a Document
 * abstraction that can be queried using CSS selectors.
 */
data class DocumentResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val document: dev.koenv.chaptervault.core.dom.Document? = null,
    val statusCode: Int? = null
) : ExecutionResult() {
    companion object {
        fun success(instructionId: String, document: dev.koenv.chaptervault.core.dom.Document, statusCode: Int = 200) =
            DocumentResult(instructionId, true, null, document, statusCode)

        fun failure(instructionId: String, error: String, statusCode: Int? = null) =
            DocumentResult(instructionId, false, error, null, statusCode)
    }
}

/**
 * Result containing structured data extracted from a document.
 *
 * The data map contains field names mapped to their extracted values:
 * - String for Text fields
 * - String for Href/Src fields
 * - List<String> for TextList fields
 * - Map<String, Any?> for Nested fields
 * - List<Map<String, Any?>> for NestedList fields
 */
data class ExtractedDataResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val url: String? = null,
    val statusCode: Int? = null
) : ExecutionResult() {

    /**
     * Get a string value from the extracted data.
     */
    fun getString(key: String): String? = data[key] as? String

    /**
     * Get a list of strings from the extracted data.
     */
    @Suppress("UNCHECKED_CAST")
    fun getStringList(key: String): List<String>? = data[key] as? List<String>

    /**
     * Get an object (nested extraction) from the extracted data.
     */
    @Suppress("UNCHECKED_CAST")
    fun getObject(key: String): Map<String, Any?>? = data[key] as? Map<String, Any?>

    /**
     * Get a list of objects (nested list extraction) from the extracted data.
     */
    @Suppress("UNCHECKED_CAST")
    fun getObjectList(key: String): List<Map<String, Any?>>? = data[key] as? List<Map<String, Any?>>

    companion object {
        fun success(instructionId: String, data: Map<String, Any?>, url: String, statusCode: Int = 200) =
            ExtractedDataResult(instructionId, true, null, data, url, statusCode)

        fun failure(instructionId: String, error: String, statusCode: Int? = null) =
            ExtractedDataResult(instructionId, false, error, emptyMap(), null, statusCode)
    }
}

/**
 * Result of a bulk download operation.
 *
 * Contains per-item results, allowing partial success (some items succeed,
 * others fail). This enables better error handling than all-or-nothing.
 */
data class BulkDownloadResult(
    override val instructionId: String,
    override val success: Boolean,
    override val error: String? = null,
    val items: Map<String, DownloadItemResult> = emptyMap()
) : ExecutionResult() {

    /**
     * Get the count of successfully downloaded items.
     */
    val successCount: Int get() = items.values.count { it.success }

    /**
     * Get the count of failed items.
     */
    val failureCount: Int get() = items.values.count { !it.success }

    /**
     * Get all successful item results.
     */
    fun successfulItems(): Map<String, DownloadItemResult> =
        items.filterValues { it.success }

    /**
     * Get all failed item results.
     */
    fun failedItems(): Map<String, DownloadItemResult> =
        items.filterValues { !it.success }

    /**
     * Iterate over successful downloads.
     */
    inline fun forEachSuccess(action: (id: String, bytes: ByteArray, mimeType: String?) -> Unit) {
        items.forEach { (id, result) ->
            if (result.success && result.bytes != null) {
                action(id, result.bytes, result.mimeType)
            }
        }
    }

    companion object {
        fun success(instructionId: String, items: Map<String, DownloadItemResult>) =
            BulkDownloadResult(instructionId, true, null, items)

        fun partialSuccess(instructionId: String, items: Map<String, DownloadItemResult>) =
            BulkDownloadResult(
                instructionId,
                items.values.any { it.success },
                if (items.values.any { !it.success }) "Some downloads failed" else null,
                items
            )

        fun failure(instructionId: String, error: String) =
            BulkDownloadResult(instructionId, false, error)
    }
}

/**
 * Result for a single item in a bulk download.
 */
data class DownloadItemResult(
    val id: String,
    val success: Boolean,
    val error: String? = null,
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val size: Long? = null
) {
    companion object {
        fun success(id: String, bytes: ByteArray, mimeType: String? = null) =
            DownloadItemResult(id, true, null, bytes, mimeType, bytes.size.toLong())

        fun failure(id: String, error: String) =
            DownloadItemResult(id, false, error)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadItemResult) return false
        return id == other.id &&
            success == other.success &&
            error == other.error &&
            bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }
}

// ============================================================================
// Result Extensions
// ============================================================================

/**
 * Require the result to be successful, throwing if not.
 */
fun <T : ExecutionResult> T.requireSuccess(): T {
    if (!success) {
        throw ExecutionException(error ?: "Execution failed", this)
    }
    return this
}

/**
 * Map a successful result to another value.
 */
inline fun <T : ExecutionResult, R> T.map(transform: (T) -> R): R? {
    return if (success) transform(this) else null
}

/**
 * Exception thrown when execution fails.
 */
class ExecutionException(
    message: String,
    val result: ExecutionResult,
    cause: Throwable? = null
) : Exception(message, cause)
