package dev.koenv.chaptervault.extensions.shared

import dev.koenv.chaptervault.kernel.extension.Capability
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry

fun ExtensionRegistry.registerWithCapabilities(
    extension: dev.koenv.chaptervault.kernel.extension.Extension,
    vararg capabilities: Capability,
) = register(extension)
