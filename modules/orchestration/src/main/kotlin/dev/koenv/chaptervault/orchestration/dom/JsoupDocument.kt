package dev.koenv.chaptervault.orchestration.dom

import dev.koenv.chaptervault.core.dom.Document
import dev.koenv.chaptervault.core.dom.Element
import org.jsoup.Jsoup
import org.jsoup.nodes.Document as JDocument

/**
 * Jsoup implementation of the Document interface.
 *
 * Wraps a Jsoup Document to provide unified DOM access.
 */
class JsoupDocument(
    private val document: JDocument,
    override val url: String
) : Document {

    override val textContent: String
        get() = document.text()

    override val html: String
        get() = document.html()

    override fun select(selector: String): List<Element> {
        return document.select(selector).map { JsoupElement(it, url) }
    }

    override fun selectFirst(selector: String): Element? {
        return document.selectFirst(selector)?.let { JsoupElement(it, url) }
    }

    companion object {
        /**
         * Parse HTML into a JsoupDocument.
         */
        fun parse(html: String, baseUrl: String = ""): JsoupDocument {
            val doc = Jsoup.parse(html, baseUrl)
            return JsoupDocument(doc, baseUrl)
        }
    }
}
