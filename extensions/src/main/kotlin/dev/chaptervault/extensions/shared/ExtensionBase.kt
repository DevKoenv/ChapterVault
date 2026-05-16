package dev.chaptervault.extensions.shared

import dev.chaptervault.kernel.extension.Extension
import dev.chaptervault.kernel.extension.ExtensionContext
import dev.chaptervault.kernel.extension.ExtensionLifecycle

abstract class ExtensionBase : Extension, ExtensionLifecycle {
    override suspend fun onLoad(context: ExtensionContext) {}
    override suspend fun onReady(context: ExtensionContext) {}
    override suspend fun onUnload() {}
}
