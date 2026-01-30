package dev.koenv.chaptervault.orchestration.dom

import dev.koenv.chaptervault.core.dom.Element
import org.jsoup.nodes.Element as JElement

/**
 * Jsoup implementation of the Element interface.
 *
 * Wraps a Jsoup Element to provide unified DOM access.
 */
class JsoupElement(
    private val element: JElement,
    private val baseUri: String = ""
) : Element {

    override val tagName: String
        get() = element.tagName()

    override val textContent: String?
        get() = element.text().takeIf { it.isNotEmpty() }

    override val innerHTML: String?
        get() = element.html().takeIf { it.isNotEmpty() }

    override fun attr(name: String): String? {
        val value = element.attr(name)
        return value.takeIf { it.isNotEmpty() }
    }

    override fun href(): String? {
        val href = element.attr("href")
        if (href.isEmpty()) return null
        return resolveUrl(href)
    }

    override fun src(): String? {
        val src = element.attr("src")
        if (src.isEmpty()) return null
        return resolveUrl(src)
    }

    override fun dataAttr(name: String): String? {
        val value = element.attr("data-$name")
        return value.takeIf { it.isNotEmpty() }
    }

    override fun select(selector: String): List<Element> {
        return element.select(selector).map { JsoupElement(it, baseUri) }
    }

    override fun selectFirst(selector: String): Element? {
        return element.selectFirst(selector)?.let { JsoupElement(it, baseUri) }
    }

    private fun resolveUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") && baseUri.isNotEmpty() -> {
                val base = baseUri.substringBefore("://") + "://" +
                    baseUri.substringAfter("://").substringBefore("/")
                "$base$url"
            }
            else -> element.attr("abs:href").ifEmpty {
                element.attr("abs:src").ifEmpty { url }
            }
        }
    }
}
