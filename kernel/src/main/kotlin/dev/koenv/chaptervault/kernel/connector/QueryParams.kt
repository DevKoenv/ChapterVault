package dev.koenv.chaptervault.kernel.connector

class QueryParams(
    private val values: Map<String, Any?> = emptyMap(),
) {
    operator fun plus(entry: Pair<String, Any?>): QueryParams = QueryParams(values + entry)

    operator fun plus(entries: Map<String, Any?>): QueryParams = QueryParams(values + entries)

    fun flatten(): List<Pair<String, String>> = flattenQueryParams(values)

    fun toMap(): Map<String, Any?> = values

    /** A readable, unencoded "key=value&..." rendering for logging; not what is sent on the wire. */
    override fun toString(): String = flatten().joinToString("&") { (k, v) -> "$k=$v" }

    companion object {
        fun of(vararg entries: Pair<String, Any?>): QueryParams = QueryParams(mapOf(*entries))
    }
}

private fun flattenQueryParams(
    params: Map<String, Any?>,
    prefix: String = "",
): List<Pair<String, String>> =
    params.flatMap { (key, value) ->
        val name = if (prefix.isEmpty()) key else "$prefix[$key]"

        when (value) {
            null -> emptyList()

            is Map<*, *> -> {
                val nested =
                    value.entries.associate { (nestedKey, nestedValue) ->
                        require(nestedKey is String) {
                            "Query parameter map keys must be strings"
                        }
                        nestedKey to nestedValue
                    }

                flattenQueryParams(nested, name)
            }

            is Iterable<*> ->
                value.flatMap { item ->
                    when (item) {
                        null -> emptyList()
                        is Map<*, *> -> {
                            val nested =
                                item.entries.associate { (nestedKey, nestedValue) ->
                                    require(nestedKey is String) {
                                        "Query parameter map keys must be strings"
                                    }
                                    nestedKey to nestedValue
                                }

                            flattenQueryParams(mapOf(name to nested))
                        }

                        else -> listOf("$name[]" to item.toString())
                    }
                }

            else -> listOf(name to value.toString())
        }
    }
