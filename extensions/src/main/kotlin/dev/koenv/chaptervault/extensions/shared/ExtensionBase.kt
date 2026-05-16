package dev.koenv.chaptervault.extensions.shared

import dev.koenv.chaptervault.kernel.extension.Extension
import dev.koenv.chaptervault.kernel.extension.ExtensionContext
import dev.koenv.chaptervault.kernel.extension.ExtensionLifecycle

abstract class ExtensionBase : Extension, ExtensionLifecycle {
    override suspend fun onLoad(context: ExtensionContext) {}
    override suspend fun onReady(context: ExtensionContext) {}
    override suspend fun onUnload() {}
}
