package dev.koenv.chaptervault.kernel.extension

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

class YamlConfig(
    val file: File,
) {
    private val data: MutableMap<String, Any?> = mutableMapOf()

    // a Yaml instance is not thread-safe, so every load and save gets its own
    private fun newYaml() = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })

    fun load(): YamlConfig {
        if (!file.exists()) return this
        val parsed: Any? =
            file.inputStream().use { stream ->
                try {
                    newYaml().load<Any?>(stream)
                } catch (e: YAMLException) {
                    throw YAMLException("Failed to parse ${file.path}: ${e.message}")
                }
            }
        data.clear()
        if (parsed != null) {
            val map =
                parsed as? Map<*, *>
                    ?: throw IllegalArgumentException(
                        "Config at ${file.path} must be a YAML mapping at the root, got ${parsed::class.simpleName}",
                    )
            data.putAll(deepMutable(map))
        }
        return this
    }

    fun save() {
        file.parentFile?.mkdirs()
        file.writer().use { newYaml().dump(data, it) }
    }

    fun getString(
        path: String,
        default: String? = null,
    ): String? = get(path) as? String ?: default

    fun getInt(
        path: String,
        default: Int? = null,
    ): Int? = (get(path) as? Number)?.toInt() ?: default

    fun getDouble(
        path: String,
        default: Double? = null,
    ): Double? = (get(path) as? Number)?.toDouble() ?: default

    fun getBoolean(
        path: String,
        default: Boolean? = null,
    ): Boolean? = get(path) as? Boolean ?: default

    fun get(path: String): Any? {
        var current: Any? = data
        for (key in path.split(".")) {
            current = (current as? Map<*, *>)?.get(key) ?: return null
        }
        return current
    }

    fun set(
        path: String,
        value: Any?,
    ) {
        val keys = path.split(".")
        var current = data
        for (key in keys.dropLast(1)) {
            val next = current[key]
            @Suppress("UNCHECKED_CAST")
            current =
                if (next is MutableMap<*, *>) {
                    next as MutableMap<String, Any?>
                } else {
                    mutableMapOf<String, Any?>().also { current[key] = it }
                }
        }
        current[keys.last()] = value
    }

    fun remove(path: String) {
        val keys = path.split(".")
        if (keys.size == 1) {
            data.remove(keys[0])
            return
        }
        @Suppress("UNCHECKED_CAST")
        (get(keys.dropLast(1).joinToString(".")) as? MutableMap<String, Any?>)?.remove(keys.last())
    }

    fun contains(path: String): Boolean {
        val keys = path.split(".")
        var current: Any? = data
        for (key in keys.dropLast(1)) {
            current = (current as? Map<*, *>)?.get(key) ?: return false
        }
        return (current as? Map<*, *>)?.containsKey(keys.last()) == true
    }

    @Suppress("UNCHECKED_CAST")
    private fun deepMutable(map: Map<*, *>): MutableMap<String, Any?> =
        map.entries
            .associate { (k, v) ->
                k.toString() to if (v is Map<*, *>) deepMutable(v) else v
            }.toMutableMap()
}
