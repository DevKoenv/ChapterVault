package dev.koenv.chaptervault.infrastructure.extensions.loader

import dev.koenv.chaptervault.kernel.extension.Extension
import java.net.URLClassLoader
import java.nio.file.Path

class LoadedExtension(
    val manifest: ExtensionManifest,
    val extension: Extension,
    val classLoader: URLClassLoader,
    val jarPath: Path,
)
