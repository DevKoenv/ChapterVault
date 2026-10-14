package dev.koenv.chaptervault.infrastructure.database

/**
 * Splits a migration file into statements. Statements end with `;`; `--` comments, blank lines and the line ending
 * style are ignored, and a `;` or `--` inside a single quoted string literal is data, not syntax. Triggers (a `;` inside
 * BEGIN ... END) are not supported.
 */
internal object SqlScript {
    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        val text = script.replace("\r\n", "\n")
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    current.append(c)
                    if (c == '\'') {
                        // a doubled quote is an escaped quote and keeps the literal open
                        if (i + 1 < text.length && text[i + 1] == '\'') {
                            current.append('\'')
                            i++
                        } else {
                            inString = false
                        }
                    }
                }
                c == '\'' -> {
                    inString = true
                    current.append(c)
                }
                c == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                c == ';' -> {
                    current
                        .toString()
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { statements += it }
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        current
            .toString()
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { statements += it }
        return statements
    }
}
