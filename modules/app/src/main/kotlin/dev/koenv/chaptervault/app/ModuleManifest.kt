package dev.koenv.chaptervault.app

import org.yaml.snakeyaml.Yaml
import java.io.InputStream

data class ModuleManifest(
    val name: String = "Unknown",
    val version: String = "unknown",
    val description: String? = null,
    val author: String? = null,
    val apiVersion: Int,
    val main: String
) {
    companion object {
        fun parse(inputStream: InputStream): ModuleManifest {
            val raw = Yaml().load<Map<String, Any>>(inputStream) ?: emptyMap()
            val apiVersion = raw["apiVersion"]?.toString()?.toIntOrNull()
                ?: throw IllegalArgumentException("module.yml missing required field: apiVersion")
            val main = raw["main"]?.toString()
                ?: throw IllegalArgumentException("module.yml missing required field: main")
            return ModuleManifest(
                name = raw["name"]?.toString() ?: "Unknown",
                version = raw["version"]?.toString() ?: "unknown",
                description = raw["description"]?.toString(),
                author = raw["author"]?.toString(),
                apiVersion = apiVersion,
                main = main
            )
        }
    }
}
