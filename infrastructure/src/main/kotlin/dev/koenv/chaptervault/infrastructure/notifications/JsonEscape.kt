package dev.koenv.chaptervault.infrastructure.notifications

/**
 * Minimal JSON string escaping for embedding in manually-built JSON literals.
 * Handles backslash, double-quote, and common control characters.
 * Known limitation: does not escape Unicode control chars U+0000-U+001F beyond \n, \r, \t,
 * or HTML special chars. Suitable for typical manga titles; sanitize inputs if arbitrary
 * user content is expected.
 */
internal fun jsonEscape(s: String): String =
    s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
