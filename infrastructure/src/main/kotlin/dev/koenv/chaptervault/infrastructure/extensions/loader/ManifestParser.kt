package dev.koenv.chaptervault.infrastructure.extensions.loader

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

object ManifestParser {
    private val SLUG_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    fun parse(content: String): ExtensionManifest? {
        if (content.isBlank()) return null
        return try {
            // a Yaml instance is not thread-safe, so every call gets its own
            @Suppress("UNCHECKED_CAST")
            val map = Yaml().load<Map<String, Any>>(content) ?: return null
            val id = map["id"] as? String ?: return null
            if (!SLUG_PATTERN.matches(id)) return null
            val name = map["name"] as? String ?: return null
            val version = map["version"] as? String ?: return null
            val minServerVersion = map["minServerVersion"] as? String ?: return null
            val description = map["description"] as? String ?: return null
            val author = map["author"] as? String ?: return null
            val priority = (map["priority"] as? Int) ?: 100
            val capabilities =
                (map["capabilities"] as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            val entryPoint = map["entryPoint"] as? String ?: return null
            ExtensionManifest(
                id = id,
                name = name,
                version = version,
                minServerVersion = minServerVersion,
                description = description,
                author = author,
                priority = priority,
                capabilities = capabilities,
                entryPoint = entryPoint,
            )
        } catch (_: YAMLException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }
}
