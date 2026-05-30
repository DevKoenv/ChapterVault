package dev.koenv.chaptervault.extensions.loader

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

object ManifestParser {
    private val yaml = Yaml()

    fun parse(content: String): ExtensionManifest? {
        if (content.isBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = yaml.load<Map<String, Any>>(content) ?: return null
            val id = map["id"] as? String ?: return null
            val name = map["name"] as? String ?: return null
            val version = map["version"] as? String ?: return null
            val minServerVersion = map["minServerVersion"] as? String ?: return null
            val description = map["description"] as? String ?: return null
            val author = map["author"] as? String ?: return null
            val priority = (map["priority"] as? Int) ?: 100
            @Suppress("UNCHECKED_CAST")
            val capabilities = (map["capabilities"] as? List<String>) ?: return null
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
