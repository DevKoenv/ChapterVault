package dev.koenv.chaptervault.extensions.loader

import dev.koenv.chaptervault.kernel.extension.Extension
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension

class ExternalExtensionLoader(
    val extensionsDir: Path,
    private val serverVersion: String,
    private val parentClassLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) {
    private val log = LoggerFactory.getLogger(ExternalExtensionLoader::class.java)

    fun loadAll(): List<Pair<ExtensionManifest, Extension>> {
        if (!Files.exists(extensionsDir)) return emptyList()
        return Files.list(extensionsDir)
            .filter { it.extension == "jar" }
            .toList()
            .mapNotNull { loadJar(it) }
    }

    private fun loadJar(jar: Path): Pair<ExtensionManifest, Extension>? {
        val manifest = readManifest(jar) ?: run {
            log.warn("Skipping ${jar.fileName}: missing or invalid extension.yaml")
            return null
        }
        if (!isCompatible(manifest)) {
            log.warn("Skipping ${manifest.id}: requires server ${manifest.minServerVersion}, running $serverVersion")
            return null
        }
        return try {
            val classLoader = URLClassLoader(arrayOf(jar.toUri().toURL()), parentClassLoader)
            val extensionClass = classLoader.loadClass(manifest.entryPoint)
            val extension = extensionClass.getDeclaredConstructor().newInstance() as Extension
            manifest to extension
        } catch (e: ClassNotFoundException) {
            log.warn("Skipping ${manifest.id}: entry point class '${manifest.entryPoint}' not found")
            null
        } catch (e: Exception) {
            log.warn("Skipping ${manifest.id}: failed to instantiate entry point -- ${e.message}")
            null
        }
    }

    private fun readManifest(jar: Path): ExtensionManifest? =
        try {
            JarFile(jar.toFile()).use { jf ->
                val entry = jf.getJarEntry("extension.yaml") ?: return null
                val content = jf.getInputStream(entry).bufferedReader().readText()
                ManifestParser.parse(content)
            }
        } catch (e: Exception) {
            log.warn("Failed to read ${jar.fileName}: ${e.message}")
            null
        }

    private fun isCompatible(manifest: ExtensionManifest): Boolean {
        val min = parseVersion(manifest.minServerVersion) ?: return false
        val server = parseVersion(serverVersion) ?: return false
        return server >= min
    }

    private fun parseVersion(version: String): IntArray? =
        try {
            version.split(".").take(3).map { it.toInt() }.toIntArray()
        } catch (_: NumberFormatException) {
            null
        }
}

private operator fun IntArray.compareTo(other: IntArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val diff = this[i] - other[i]
        if (diff != 0) return diff
    }
    return size - other.size
}
