package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.extension.ConfigFieldType
import dev.koenv.chaptervault.kernel.extension.ExtensionConfigField
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
            val capabilities =
                (map["capabilities"] as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            val entryPoint = map["entryPoint"] as? String ?: return null
            val config = parseConfigFields(map["config"])
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
                config = config,
            )
        } catch (_: YAMLException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfigFields(raw: Any?): List<ExtensionConfigField> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<String, Any> ?: return@mapNotNull null
            val key = m["key"] as? String ?: return@mapNotNull null
            val label = m["label"] as? String ?: return@mapNotNull null
            val typeStr = m["type"] as? String ?: return@mapNotNull null
            val type = runCatching { ConfigFieldType.valueOf(typeStr) }.getOrNull() ?: return@mapNotNull null
            ExtensionConfigField(
                key = key,
                label = label,
                type = type,
                required = (m["required"] as? Boolean) ?: false,
                default = m["default"] as? String,
                description = m["description"] as? String,
            )
        }
    }
}
