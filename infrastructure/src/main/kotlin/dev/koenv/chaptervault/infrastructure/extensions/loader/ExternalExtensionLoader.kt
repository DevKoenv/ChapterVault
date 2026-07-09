package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.extension.Extension
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension

private val logger = KotlinLogging.logger {}

class ExternalExtensionLoader(
    val extensionsDir: Path,
    private val serverVersion: String,
    private val parentClassLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) {
    fun loadAll(): List<LoadedExtension> {
        Files.createDirectories(extensionsDir)
        val seen = mutableMapOf<String, String>() // id -> first jar filename
        return Files
            .list(extensionsDir)
            .filter { it.extension == "jar" }
            .toList()
            .mapNotNull { jar ->
                val loaded = loadSingle(jar) ?: return@mapNotNull null
                val id = loaded.manifest.id
                val existing = seen[id]
                if (existing != null) {
                    logger.warn { "Duplicate extension id '$id': already loaded from '$existing', skipping '${jar.fileName}'." }
                    return@mapNotNull null
                }
                seen[id] = jar.fileName.toString()
                loaded
            }
    }

    fun loadSingle(jar: Path): LoadedExtension? {
        val manifest =
            readManifest(jar) ?: run {
                logger.warn { "Skipping ${jar.fileName}: missing or invalid extension.yaml" }
                return null
            }
        if (!isCompatible(manifest)) {
            logger.warn { "Skipping ${manifest.id}: requires server ${manifest.minServerVersion}, running $serverVersion" }
            return null
        }
        val classLoader = URLClassLoader(arrayOf(jar.toUri().toURL()), parentClassLoader)
        return try {
            val extensionClass = classLoader.loadClass(manifest.entryPoint)
            val extension = extensionClass.getDeclaredConstructor().newInstance() as Extension
            if (extension.id != manifest.id) {
                logger.warn {
                    "Extension '${jar.fileName}' has manifest id '${manifest.id}' but Extension.id returned '${extension.id}'. " +
                        "The manifest value will be used. Update Extension.id to match the manifest."
                }
            }
            LoadedExtension(manifest = manifest, extension = extension, classLoader = classLoader, jarPath = jar)
        } catch (e: ClassNotFoundException) {
            classLoader.close()
            logger.warn { "Skipping ${manifest.id}: entry point class '${manifest.entryPoint}' not found" }
            null
        } catch (e: Exception) {
            classLoader.close()
            logger.warn { "Skipping ${manifest.id}: failed to instantiate entry point -- ${e.message}" }
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
            logger.warn { "Failed to read ${jar.fileName}: ${e.message}" }
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
