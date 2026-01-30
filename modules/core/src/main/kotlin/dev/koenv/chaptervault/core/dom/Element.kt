package dev.koenv.chaptervault.core.dom

/**
 * Unified element abstraction for both Jsoup and browser elements.
 *
 * Provides a common interface to access element properties regardless
 * of the underlying implementation (Jsoup Element or browser ElementData).
 */
interface Element {
    /**
     * The tag name of this element (e.g., "div", "a", "img").
     */
    val tagName: String

    /**
     * The text content of this element (recursively includes child text).
     */
    val textContent: String?

    /**
     * The inner HTML of this element.
     */
    val innerHTML: String?

    /**
     * Get an attribute value by name.
     */
    fun attr(name: String): String?

    /**
     * Get the href attribute, resolving relative URLs if possible.
     */
    fun href(): String?

    /**
     * Get the src attribute, resolving relative URLs if possible.
     */
    fun src(): String?

    /**
     * Get a data attribute value (e.g., dataAttr("id") returns data-id).
     */
    fun dataAttr(name: String): String?

    /**
     * Select all descendant elements matching the CSS selector.
     */
    fun select(selector: String): List<Element>

    /**
     * Select the first descendant element matching the CSS selector.
     */
    fun selectFirst(selector: String): Element?
}
