package dev.koenv.chaptervault.infrastructure.extensions.loader

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

    fun loadAll(): List<LoadedExtension> {
        Files.createDirectories(extensionsDir)
        return Files
            .list(extensionsDir)
            .filter { it.extension == "jar" }
            .toList()
            .mapNotNull { loadSingle(it) }
    }

    fun loadSingle(jar: Path): LoadedExtension? {
        val manifest =
            readManifest(jar) ?: run {
                log.warn("Skipping ${jar.fileName}: missing or invalid extension.yaml")
                return null
            }
        if (!isCompatible(manifest)) {
            log.warn("Skipping ${manifest.id}: requires server ${manifest.minServerVersion}, running $serverVersion")
            return null
        }
        // URLClassLoader stays open while the extension is active; release on UNLOADED is TODO(Plan2)
        val classLoader = URLClassLoader(arrayOf(jar.toUri().toURL()), parentClassLoader)
        return try {
            val extensionClass = classLoader.loadClass(manifest.entryPoint)
            val extension = extensionClass.getDeclaredConstructor().newInstance() as Extension
            LoadedExtension(manifest = manifest, extension = extension, classLoader = classLoader, jarPath = jar)
        } catch (e: ClassNotFoundException) {
            classLoader.close()
            log.warn("Skipping ${manifest.id}: entry point class '${manifest.entryPoint}' not found")
            null
        } catch (e: Exception) {
            classLoader.close()
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
            version
                .split(".")
                .take(3)
                .map { it.toInt() }
                .toIntArray()
        } catch (_: NumberFormatException) {
            null
        }
}

private operator fun IntArray.compareTo(other: IntArray): Int =
    zip(other.toList()).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: size.compareTo(other.size)
