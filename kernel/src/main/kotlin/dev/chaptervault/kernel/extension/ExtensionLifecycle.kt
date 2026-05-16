package dev.chaptervault.kernel.extension

interface ExtensionLifecycle {
    // Called during startup — register capabilities only, no side effects
    suspend fun onLoad(context: ExtensionContext)

    // Called after server is fully started — safe to do post-boot work
    suspend fun onReady(context: ExtensionContext) {}

    // Called during shutdown
    suspend fun onUnload() {}
}
