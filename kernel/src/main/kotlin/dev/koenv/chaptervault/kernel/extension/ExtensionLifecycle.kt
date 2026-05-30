package dev.koenv.chaptervault.kernel.extension

interface ExtensionLifecycle {
    // register capabilities only, no side effects
    suspend fun onLoad(context: ExtensionContext)

    suspend fun onReady(context: ExtensionContext) {}

    suspend fun onUnload() {}
}
