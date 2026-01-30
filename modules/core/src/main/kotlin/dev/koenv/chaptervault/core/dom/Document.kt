package dev.koenv.chaptervault.core.dom

/**
 * Unified DOM abstraction for both HTTP (Jsoup) and browser (ElementData) results.
 *
 * This interface provides a common way to work with parsed HTML content
 * regardless of whether it came from:
 * - HTTP fetch (parsed by Jsoup)
 * - Browser query (ElementData from JavaScript)
 *
 * Implementations:
 * - JsoupDocument: wraps org.jsoup.nodes.Document
 * - ElementDataDocument: wraps browser ElementData results
 */
interface Document {
    /**
     * The URL of this document.
     */
    val url: String

    /**
     * Select all elements matching the CSS selector.
     */
    fun select(selector: String): List<Element>

    /**
     * Select the first element matching the CSS selector.
     */
    fun selectFirst(selector: String): Element?

    /**
     * Get the document's text content.
     */
    val textContent: String

    /**
     * Get the document's HTML content.
     */
    val html: String
}
